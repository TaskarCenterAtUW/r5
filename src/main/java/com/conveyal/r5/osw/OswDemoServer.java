package com.conveyal.r5.osw;

import com.conveyal.r5.SoftwareVersion;
import com.conveyal.r5.analyst.cluster.TransportNetworkConfig;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Response;
import spark.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A small standalone web server for demonstrating pedestrian cost profiles on an OpenSidewalks network: it loads one
 * OSW dataset and a directory of profile files, and serves a single-page map (resources/osw-demo) where you pick a
 * profile, adjust its parameters, click a starting point and see the walkshed. It needs no database and is separate
 * from the Conveyal analysis backend.
 *
 * Usage:
 *   OswDemoServer --osw DATASET [--profiles DIR] [--port 8080] [--static DIR]
 *                 [--dataset-id ID] [--walksheds-profile KEY] [--walksheds-compat true|false]
 *
 * --profiles defaults to osw-tools/profiles. Profile files are re-read on every request, so edits show up on reload.
 * --static serves the page from a directory instead of the jar, for working on the page without rebuilding.
 * --dataset-id and --walksheds-profile configure the Walksheds-compatible API under /api/v1 (see WalkshedsApi).
 * --walksheds-compat, or the environment variable WALKSHEDS_COMPAT where the option is not given, says whether that
 * API attaches points to the network as Walksheds does (the default) or as R5 does.
 *
 * API (all JSON):
 *   GET  /api/info                                  network summary, R5 version, available profiles
 *   GET  /api/network?profile=KEY&parameters=JSON   every OSW edge, with its layer and which directions are usable
 *   POST /api/walkshed  {profile, parameters, walkSpeed, maxMinutes, lat, lon}   reachable edges with arrival times
 */
public class OswDemoServer {

    private static final Logger LOG = LoggerFactory.getLogger(OswDemoServer.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TransportNetwork network;
    final StreetLayer streets;
    final OswEdgeAttributes attrs;
    private final File profilesDir;
    private final String datasetName;
    private final String inputMd5;

    /** The dataset ID and profile used by the Walksheds-compatible API. */
    String walkshedsDatasetId;
    String walkshedsProfile = "ws-prod";

    /**
     * Whether the Walksheds-compatible API copies what Walksheds does where R5 would otherwise do something more
     * accurate: at present, where a point attaches to the network (see WalkshedsSnapping).
     */
    boolean walkshedsCompat = !isOff(System.getenv("WALKSHEDS_COMPAT"));

    public OswDemoServer (String oswPath, File profilesDir) throws IOException {
        TransportNetworkConfig config = new TransportNetworkConfig();
        // Keep small disconnected pieces of the network, as Unweaver does.
        config.pruneIslands = false;
        this.network = TransportNetwork.fromOsw(oswPath, config);
        this.streets = network.streetLayer;
        this.attrs = streets.edgeStore.oswAttributes;
        this.profilesDir = profilesDir;
        this.datasetName = OswReader.datasetName(oswPath);
        this.inputMd5 = OswWalkshedMain.md5OfInput(new File(oswPath));
        this.walkshedsDatasetId = datasetName;
    }

    public static void main (String[] args) throws Exception {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) opts.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        if (!opts.containsKey("osw")) {
            System.err.println("Usage: OswDemoServer --osw DATASET [--profiles DIR] [--port 8080] [--static DIR] "
                    + "[--dataset-id ID] [--walksheds-profile KEY] [--walksheds-compat true|false]");
            System.exit(1);
        }
        File profiles = new File(opts.getOrDefault("profiles", "osw-tools/profiles"));
        if (!profiles.isDirectory()) {
            System.err.println("Profiles directory not found: " + profiles.getAbsolutePath());
            System.exit(1);
        }
        OswDemoServer server = new OswDemoServer(opts.get("osw"), profiles);
        if (opts.containsKey("dataset-id")) server.walkshedsDatasetId = opts.get("dataset-id");
        if (opts.containsKey("walksheds-profile")) server.walkshedsProfile = opts.get("walksheds-profile");
        if (opts.containsKey("walksheds-compat")) server.walkshedsCompat = !isOff(opts.get("walksheds-compat"));
        LOG.info("Walksheds compatibility mode is {}.", server.walkshedsCompat ? "on" : "off");
        int port = Integer.parseInt(opts.getOrDefault("port", "8080"));
        server.start(port, opts.get("static"));
        System.out.printf("OSW demo running at http://localhost:%d%n", port);
    }

    /** Whether a setting that is on unless turned off has been turned off. */
    private static boolean isOff (String value) {
        return value != null && List.of("0", "false", "no", "off").contains(value.trim().toLowerCase());
    }

    public Service start (int port, String staticDir) {
        Service http = Service.ignite().port(port);
        if (staticDir != null) {
            http.staticFiles.externalLocation(new File(staticDir).getAbsolutePath());
        } else {
            http.staticFiles.location("/osw-demo");
        }
        http.before("/api/*", (req, res) -> res.type("application/json"));
        http.get("/api/info", (req, res) -> MAPPER.writeValueAsString(info()));
        http.get("/api/network", (req, res) -> {
            PedestrianCostSpec spec = spec(req.queryParams("profile"), parseObject(req.queryParams("parameters")));
            res.header("Content-Encoding", "gzip");
            return MAPPER.writeValueAsString(networkGeoJson(spec));
        });
        http.post("/api/walkshed", (req, res) -> {
            res.header("Content-Encoding", "gzip");
            return MAPPER.writeValueAsString(walkshed(MAPPER.readTree(req.body())));
        });
        new WalkshedsApi(this).register(http);
        http.exception(IllegalArgumentException.class, (e, req, res) -> error(res, 400, e.getMessage()));
        http.exception(UnsupportedOperationException.class, (e, req, res) -> error(res, 501, e.getMessage()));
        http.exception(Exception.class, (e, req, res) -> {
            LOG.error("Request failed", e);
            error(res, 500, e.toString());
        });
        http.awaitInitialization();
        return http;
    }

    static void error (Response res, int status, String message) {
        res.status(status);
        res.type("application/json");
        ObjectNode body = MAPPER.createObjectNode();
        body.put("error", message);
        res.body(body.toString());
    }

    private ObjectNode info () throws IOException {
        ObjectNode out = MAPPER.createObjectNode();
        ObjectNode net = out.putObject("network");
        net.put("name", datasetName);
        net.put("inputMd5", inputMd5);
        Envelope env = streets.envelope;
        net.putArray("bounds").add(env.getMinX()).add(env.getMinY()).add(env.getMaxX()).add(env.getMaxY());
        net.put("edges", attrs.nPairs());
        ObjectNode r5 = out.putObject("r5");
        r5.put("version", SoftwareVersion.instance.version);
        r5.put("commit", SoftwareVersion.instance.commit);
        ArrayNode profiles = out.putArray("profiles");
        File[] files = profilesDir.listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File f : files) {
                ObjectNode p = profiles.addObject();
                p.put("key", profileKey(f));
                try {
                    PedestrianCostProfile profile = PedestrianCostProfile.fromFile(f);
                    p.put("name", profile.name);
                    p.put("description", profile.source.path("description").asText(""));
                    p.put("profileId", profile.profileId);
                    p.set("parameters", profile.source.path("parameters"));
                } catch (Exception e) {
                    // Show broken profiles in the list with their error, rather than hiding them.
                    p.put("name", profileKey(f));
                    p.put("error", e.getMessage());
                }
            }
        }
        return out;
    }

    private static String profileKey (File f) {
        return f.getName().replaceFirst("\\.json$", "");
    }

    PedestrianCostProfile profile (String key) throws IOException {
        if (key == null || !key.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Unknown profile " + key);
        File f = new File(profilesDir, key + ".json");
        if (!f.isFile()) throw new IllegalArgumentException("Unknown profile " + key);
        return PedestrianCostProfile.fromFile(f);
    }

    PedestrianCostSpec spec (String key, JsonNode parameters) throws IOException {
        Map<String, Object> params = parameters == null || parameters.isNull()
                ? new HashMap<>() : MAPPER.convertValue(parameters, Map.class);
        return profile(key).resolve(params);
    }

    private static JsonNode parseObject (String json) throws IOException {
        return (json == null || json.isEmpty()) ? null : MAPPER.readTree(json);
    }

    /** Every OSW edge pair, with its layer under the spec and whether each direction can be traversed. */
    private ObjectNode networkGeoJson (PedestrianCostSpec spec) {
        PedestrianCostTable table = attrs.costTable(spec, streets.edgeStore);
        ObjectNode fc = MAPPER.createObjectNode();
        fc.put("type", "FeatureCollection");
        ArrayNode features = fc.putArray("features");
        EdgeStore.Edge edge = streets.edgeStore.getCursor();
        for (int e = 0; e < streets.edgeStore.nEdges(); e += 2) {
            Map<String, String> tags = attrs.tags(e);
            if (tags == null) continue;
            edge.seek(e);
            ObjectNode f = features.addObject();
            f.put("type", "Feature");
            f.set("geometry", lineJson(edge.getGeometry()));
            ObjectNode p = f.putObject("properties");
            p.put("id", attrs.ids.edgeId(edge.getOSMID()));
            p.put("layer", spec.layerName(tags));
            p.put("forward", !Double.isNaN(table.speedFactor(e)));
            p.put("backward", !Double.isNaN(table.speedFactor(e + 1)));
        }
        return fc;
    }

    /**
     * Route from a point with the profile, and return the reachable parts of the network: each directed edge whose
     * start is reached is drawn with the times at which its ends are reached, cut short where the time runs out.
     */
    private ObjectNode walkshed (JsonNode body) throws IOException {
        long start = System.currentTimeMillis();
        PedestrianCostSpec spec = spec(body.path("profile").asText(null), body.get("parameters"));
        double walkSpeed = body.path("walkSpeed").asDouble(1.3);
        if (walkSpeed <= 0 || walkSpeed > 10) throw new IllegalArgumentException("Walk speed must be 0–10 m/s.");
        double maxMinutes = body.path("maxMinutes").asDouble(5);
        if (maxMinutes <= 0 || maxMinutes > 120) throw new IllegalArgumentException("Time limit must be 1–120 minutes.");
        int limit = (int) Math.round(maxMinutes * 60);
        double lat = body.path("lat").asDouble(), lon = body.path("lon").asDouble();

        ProfileRequest req = new ProfileRequest();
        req.walkSpeed = (float) walkSpeed;
        req.pedestrianCost = new PedestrianCostRequest(spec.profile.source, MAPPER.convertValue(
                MAPPER.valueToTree(spec.parameterValues), Map.class));
        StreetRouter router = new StreetRouter(streets);
        router.profileRequest = req;
        router.streetMode = StreetMode.WALK;
        router.timeLimitSeconds = limit;
        if (!router.setOrigin(lat, lon)) {
            throw new IllegalArgumentException("There is no path within 300 m of that point. Choose a point closer to the network.");
        }
        router.route();
        PedestrianCostTable table = router.getPedestrianCostTable();

        ObjectNode out = MAPPER.createObjectNode();
        ObjectNode prov = out.putObject("provenance");
        spec.provenance().forEach(prov::put);
        prov.put("networkInputMd5", inputMd5);
        prov.put("baseSpeed", walkSpeed);
        ObjectNode origin = out.putObject("origin");
        origin.put("lat", lat);
        origin.put("lon", lon);
        Split split = router.getOriginSplit();
        String originLayer = spec.layerName(attrs.tags(split.edge));
        origin.put("layer", originLayer);
        // Where the search actually starts: the nearest point on the network, which the page marks.
        origin.put("snappedLat", VertexStore.fixedDegreesToFloating(split.fixedLat));
        origin.put("snappedLon", VertexStore.fixedDegreesToFloating(split.fixedLon));
        origin.put("offNetworkMeters", Math.round(split.distanceToEdge_mm / 100.0) / 10.0);

        ObjectNode fc = out.putObject("walkshed");
        fc.put("type", "FeatureCollection");
        ArrayNode features = fc.putArray("features");
        double totalMeters = 0;
        for (Piece p : reachedPieces(router, table, limit)) totalMeters += addFeatures(features, p, spec);
        ObjectNode stats = out.putObject("stats");
        stats.put("reachableMeters", Math.round(totalMeters));
        stats.put("segments", features.size());
        stats.put("milliseconds", System.currentTimeMillis() - start);
        return out;
    }

    /**
     * Every stretch of the network reached by a finished search, each walked in the direction that reaches it first
     * and cut short where the time runs out.
     */
    List<Piece> reachedPieces (StreetRouter router, PedestrianCostTable table, double limit) {
        List<Piece> pieces = new ArrayList<>();
        Split split = router.getOriginSplit();
        double walkSpeed = router.profileRequest.walkSpeed;
        EdgeStore.Edge fwd = streets.edgeStore.getCursor();
        EdgeStore.Edge back = streets.edgeStore.getCursor();
        for (int e = 0; e < streets.edgeStore.nEdges(); e += 2) {
            if (attrs.tags(e) == null) continue;
            fwd.seek(e);
            back.seek(e + 1);
            double tFwd = router.getExactTravelTimeToVertex(fwd.getFromVertex());
            double tBack = router.getExactTravelTimeToVertex(back.getFromVertex());
            if (e == split.edge) {
                // The search starts partway along this edge, so it is walked outward from that point in both
                // directions (as the router does), and only otherwise inward from its ends.
                double at = splitFraction(split);
                double meters0 = split.distance0_mm / 1000d, meters1 = split.distance1_mm / 1000d;
                int offStreet = offStreetSeconds(split, walkSpeed);
                addBest(pieces,
                        piece(fwd, at, 1, meters1, offStreet, table, walkSpeed, limit),
                        piece(back, 0, 1 - at, meters1, tBack, table, walkSpeed, limit));
                addBest(pieces,
                        piece(back, 1 - at, 1, meters0, offStreet, table, walkSpeed, limit),
                        piece(fwd, 0, at, meters0, tFwd, table, walkSpeed, limit));
            } else {
                addBest(pieces,
                        piece(fwd, 0, 1, fwd.getLengthM(), tFwd, table, walkSpeed, limit),
                        piece(back, 0, 1, back.getLengthM(), tBack, table, walkSpeed, limit));
            }
        }
        return pieces;
    }

    /** @return how far along the forward edge of its pair a split point lies, from 0 to 1, by ground distance. */
    double splitFraction (Split split) {
        LineString line = streets.edgeStore.getCursor(split.edge).getGeometry();
        return new GroundLine(line).fractionAt(new Coordinate(
                VertexStore.fixedDegreesToFloating(split.fixedLon), VertexStore.fixedDegreesToFloating(split.fixedLat)));
    }

    /** The time the router allows for getting from the requested point to the network, as in StreetRouter.setOrigin. */
    static int offStreetSeconds (Split split, double walkSpeed) {
        return split.distanceToEdge_mm / (int) ((float) walkSpeed * 1000);
    }

    /** A walk along all or part of a directed edge, between the times (in seconds) at which its ends are reached. */
    static class Piece {
        int edgeIndex;
        long osmId;
        double t0, t1, meters;
        /** Whether the walk gets to the end of its stretch, and whether that stretch is the whole edge. */
        boolean complete, wholeEdge;
        LineString geometry;
    }

    /**
     * The walk along a directed edge from fraction "from" to fraction "to" of its geometry, which is the given number
     * of meters, starting at tStart seconds and cut short where the time runs out. Null if the start is unreached or
     * the edge is unusable in this direction.
     */
    Piece piece (EdgeStore.Edge edge, double from, double to, double meters, double tStart,
                         PedestrianCostTable table, double walkSpeed, double limit) {
        boolean whole = from == 0 && to == 1;
        if (tStart >= limit) return null;
        if (!whole && meters < PedestrianCostTable.AT_END_METERS) return null; // Nothing to walk: see partialSeconds.
        // In fractions of a second, as the router keeps them.
        double seconds = whole ? table.seconds(edge, walkSpeed) : table.partialSeconds(edge, walkSpeed, meters);
        if (Double.isNaN(seconds)) return null;
        Piece p = new Piece();
        p.edgeIndex = edge.getEdgeIndex();
        p.osmId = edge.getOSMID();
        p.t0 = tStart;
        p.complete = tStart + seconds <= limit;
        p.wholeEdge = whole;
        double reached = p.complete ? 1 : (limit - tStart) / seconds;
        p.t1 = p.complete ? tStart + seconds : limit;
        p.meters = meters * reached;
        LineString line = edge.getGeometry();
        p.geometry = (whole && p.complete) ? line : new GroundLine(line).extract(from, from + (to - from) * reached);
        return p;
    }

    /**
     * A line that can be cut by fractions of its length on the ground. Lengths in degrees won't do: a degree of
     * longitude is shorter than one of latitude, so they misplace points on lines that change direction.
     */
    static class GroundLine {
        final double xScale, length;
        final LengthIndexedLine indexed;

        GroundLine (LineString line) {
            xScale = Math.cos(Math.toRadians(line.getCoordinateN(0).y));
            LineString scaled = line.getFactory().createLineString(scaleX(line, xScale));
            length = scaled.getLength();
            indexed = new LengthIndexedLine(scaled);
        }

        /** @return how far along the line the nearest point to c is, from 0 to 1. */
        double fractionAt (Coordinate c) {
            return length == 0 ? 0 : indexed.project(new Coordinate(c.x * xScale, c.y)) / length;
        }

        LineString extract (double from, double to) {
            LineString part = (LineString) indexed.extractLine(from * length, to * length);
            return part.getFactory().createLineString(scaleX(part, 1 / xScale));
        }

        private static Coordinate[] scaleX (LineString line, double scale) {
            Coordinate[] scaled = new Coordinate[line.getNumPoints()];
            for (int i = 0; i < scaled.length; i++) {
                Coordinate c = line.getCoordinateN(i);
                scaled[i] = new Coordinate(c.x * scale, c.y);
            }
            return scaled;
        }
    }

    /**
     * Choose how to show one stretch of an edge given the walks along it in each direction: one complete walk covers
     * it (the earlier, if both are complete); otherwise keep what each direction reaches.
     */
    private static void addBest (List<Piece> pieces, Piece a, Piece b) {
        Piece best = null;
        if (a != null && a.complete) best = a;
        if (b != null && b.complete && (best == null || b.t1 < best.t1)) best = b;
        if (best != null) {
            pieces.add(best);
        } else {
            if (a != null) pieces.add(a);
            if (b != null) pieces.add(b);
        }
    }

    /**
     * Add a piece to the output, cut at each whole minute so that the page can colour a long edge by the time each
     * part of it is reached rather than in one colour.
     */
    private double addFeatures (ArrayNode features, Piece p, PedestrianCostSpec spec) {
        String id = attrs.ids.edgeId(p.osmId);
        String layer = spec.layerName(attrs.tags(p.edgeIndex));
        double span = p.t1 - p.t0;
        GroundLine line = new GroundLine(p.geometry);
        double from = p.t0;
        while (true) {
            double to = Math.min(p.t1, (Math.floor(from / 60) + 1) * 60);
            boolean last = to >= p.t1;
            ObjectNode f = features.addObject();
            f.put("type", "Feature");
            f.set("geometry", lineJson(span <= 0 ? p.geometry
                    : line.extract((from - p.t0) / span, (to - p.t0) / span)));
            ObjectNode props = f.putObject("properties");
            props.put("id", id);
            props.put("layer", layer);
            props.put("t0", Math.round(from / 6.0) / 10.0); // minutes, one decimal
            props.put("t1", Math.round(to / 6.0) / 10.0);
            props.put("partial", last && !p.complete);
            if (last) break;
            from = to;
        }
        return p.meters;
    }

    static ObjectNode lineJson (LineString line) {
        ObjectNode g = MAPPER.createObjectNode();
        g.put("type", "LineString");
        ArrayNode coords = g.putArray("coordinates");
        for (Coordinate c : line.getCoordinates()) {
            coords.addArray().add(Math.round(c.x * 1e7) / 1e7).add(Math.round(c.y * 1e7) / 1e7);
        }
        return g;
    }
}
