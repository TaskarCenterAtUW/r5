package com.conveyal.r5.osw;

import com.conveyal.r5.osw.OswDemoServer.GroundLine;
import com.conveyal.r5.osw.OswDemoServer.Piece;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import spark.Request;
import spark.Response;
import spark.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A stand-in for the routing API of the TDEI Walksheds service (an Unweaver server), covering the calls made by the
 * TDEI quality reports, so those can be run against R5 by pointing WALKSHEDS_URL at http://HOST:PORT/api/v1.
 *
 *   GET /api/v1/router/status                           {"status": "ready", "dataset_id": ...}
 *   GET /api/v1/router/build?dataset_id=ID              Ok if ID is the loaded dataset; this server can't load others
 *   GET /api/v1/router/lock, /api/v1/router/unlock      accepted and ignored
 *   GET /api/v1/routing/reachable_tree/custom.json?lon&lat&max_cost[&uphill&downhill&avoidCurbs&streetAvoidance&fanOut]
 *   GET /api/v1/routing/shortest_path/custom.json?lon1&lat1&lon2&lat2[&uphill&downhill&avoidCurbs&streetAvoidance&fanOut]
 *
 * Costs are those of the server's --walksheds-profile at 1.3 m/s, with uphill, downhill, avoidCurbs and
 * streetAvoidance passed to it as its uphill, downhill, avoid_curbs and street_avoidance parameters, where it has
 * them. The default profile, ws-prod, follows the cost function on the production Walksheds server.
 *
 * Not the same as Walksheds:
 *  - reverse is not implemented: requests that turn it on get a 501.
 *  - fanOut ("fan mode") costs streets like pedestrian edges, by length and incline with no street penalty, whatever
 *    streetAvoidance the request gives. It sets the profile's fan_out parameter; a profile without one (walksheds,
 *    the cost function from before fan mode) gets a 501.
 *  - The profile leaves out parts of the Walksheds cost function: see its description.
 *  - Points snap to the nearest edge within 50 m that the traveller can use in at least one direction. Walksheds
 *    chooses among a few nearby edges, so it may pick differently where the nearest usable edge is one-way.
 *  - Times are R5's: each edge is rounded up to a whole second.
 *  - A point within 10 cm of a node is treated as at the node. One slightly further along an edge is charged that
 *    edge's delay, or gets NoPath if the edge is impassable; R5's snapping can place a point given at a node there.
 *  - Edge features carry the OSW properties (with ":" in keys replaced by "_", as in Walksheds), but not the counts
 *    Walksheds derives (curbs, lowered_curbs, flush_curbs), and _u and _v never carry a third (elevation) part.
 */
class WalkshedsApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Unweaver's WALK_BASE, in meters per second. */
    private static final float BASE_SPEED = 1.3f;

    /**
     * Points further than this from a usable edge, in meters, are invalid waypoints. Unweaver's default (DWITHIN) is
     * 30, but routes from the deployed Walksheds start up to 50 m from the requested point and no further.
     */
    private static final int MAX_SNAP_METERS = 50;

    private final OswDemoServer server;
    private final StreetLayer streets;
    private final OswEdgeAttributes attrs;

    WalkshedsApi (OswDemoServer server) {
        this.server = server;
        this.streets = server.streets;
        this.attrs = server.attrs;
    }

    void register (Service http) {
        String base = "/api/v1";
        http.get(base + "/router/status", (req, res) -> status().toString());
        http.get(base + "/router/build", (req, res) -> build(req).toString());
        http.get(base + "/router/lock", (req, res) -> code("Ok").toString());
        http.get(base + "/router/unlock", (req, res) -> code("Ok").toString());
        http.get(base + "/routing/reachable_tree/custom.json", (req, res) -> send(res, reachableTree(req)));
        http.get(base + "/routing/shortest_path/custom.json", (req, res) -> send(res, shortestPath(req)));
    }

    private static String send (Response res, ObjectNode body) throws IOException {
        res.header("Content-Encoding", "gzip");
        return MAPPER.writeValueAsString(body);
    }

    private static ObjectNode code (String code) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("code", code);
        return out;
    }

    // ------------------------------------------------------------------------------------------------ Router

    private ObjectNode status () {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "ready");
        out.put("dataset_id", server.walkshedsDatasetId);
        return out;
    }

    private ObjectNode build (Request req) {
        String requested = req.queryParams("dataset_id");
        if (requested == null) throw new IllegalArgumentException("dataset_id is required.");
        if (!requested.equals(server.walkshedsDatasetId)) {
            throw new UnsupportedOperationException("Not implemented: this server only serves the dataset it was "
                    + "started with (" + server.walkshedsDatasetId + "). Start it with --osw and --dataset-id for "
                    + requested + ".");
        }
        return code("Ok");
    }

    // ------------------------------------------------------------------------------------------------ Parameters

    private static double number (Request req, String name) {
        String value = req.queryParams(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(name + " is required.");
        try {
            double d = Double.parseDouble(value);
            if (Double.isFinite(d)) return d;
        } catch (NumberFormatException e) {
            // Reported below.
        }
        throw new IllegalArgumentException(name + " must be a number.");
    }

    /** Booleans arrive as 0/1 or as Python's True/False. */
    private static boolean flag (Request req, String name) {
        String value = req.queryParams(name);
        if (value == null) return false;
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "": case "0": case "0.0": case "false": case "no": case "none": return false;
            case "1": case "1.0": case "true": case "yes": return true;
            default: throw new IllegalArgumentException(name + " must be true or false.");
        }
    }

    /** The cost function for a request, after refusing options this server does not have. */
    private PedestrianCostSpec spec (Request req) throws IOException {
        if (flag(req, "reverse")) throw new UnsupportedOperationException("Not implemented: reverse");
        ObjectNode parameters = MAPPER.createObjectNode();
        if (req.queryParams("uphill") != null) parameters.put("uphill", number(req, "uphill"));
        if (req.queryParams("downhill") != null) parameters.put("downhill", number(req, "downhill"));
        if (req.queryParams("avoidCurbs") != null) parameters.put("avoid_curbs", flag(req, "avoidCurbs"));
        if (req.queryParams("streetAvoidance") != null) {
            parameters.put("street_avoidance", number(req, "streetAvoidance"));
        }
        // A profile need not have all of these: one with no streets has nothing to avoid.
        PedestrianCostProfile profile = server.profile(server.walkshedsProfile);
        if (flag(req, "fanOut")) {
            // Fan mode: streets cost what a pedestrian edge of the same length and incline would, with no penalty.
            if (!profile.parameters.containsKey("fan_out")) {
                throw new UnsupportedOperationException("Not implemented: fanOut with profile " + profile.name);
            }
            parameters.put("fan_out", true);
        }
        parameters.retain(profile.parameters.keySet());
        return profile.resolve(MAPPER.convertValue(parameters, Map.class));
    }

    private StreetRouter router (PedestrianCostSpec spec) {
        ProfileRequest request = new ProfileRequest();
        request.walkSpeed = BASE_SPEED;
        request.pedestrianCost = new PedestrianCostRequest(spec.profile.source, MAPPER.convertValue(
                MAPPER.valueToTree(spec.parameterValues), Map.class));
        StreetRouter router = new StreetRouter(streets);
        router.profileRequest = request;
        router.streetMode = StreetMode.WALK;
        return router;
    }

    /**
     * The nearest point on an edge the traveller can use in at least one direction, or null if there is none within
     * MAX_SNAP_METERS. Like Unweaver, this passes over nearer edges that are impassable under the cost function.
     */
    private Split snap (StreetRouter router, double lat, double lon) {
        PedestrianCostTable table = router.getPedestrianCostTable();
        return Split.find(lat, lon, MAX_SNAP_METERS, streets, StreetMode.WALK,
                e -> !Double.isNaN(table.speedFactor(e)) || !Double.isNaN(table.speedFactor(e + 1)));
    }

    // ------------------------------------------------------------------------------------------------ Reachable tree

    private ObjectNode reachableTree (Request req) throws IOException {
        double lon = number(req, "lon"), lat = number(req, "lat"), maxCost = number(req, "max_cost");
        if (maxCost < 1) throw new IllegalArgumentException("max_cost must be at least 1.");
        StreetRouter router = router(spec(req));
        Split split = snap(router, lat, lon);
        if (split == null) return code("InvalidWaypoint");
        router.setOrigin(split, lat, lon);
        // Unweaver's costs start on the network, so the time R5 allows for getting there is not counted.
        int offStreet = OswDemoServer.offStreetSeconds(split, BASE_SPEED);
        int limit = (int) Math.floor(maxCost) + offStreet;
        router.timeLimitSeconds = limit;
        router.route();
        List<Piece> pieces = server.reachedPieces(router, router.getPedestrianCostTable(), limit);
        if (pieces.isEmpty()) return code("InvalidWaypoint");

        ObjectNode out = code("Ok");
        out.put("status", "Ok");
        out.set("origin", point(VertexStore.fixedDegreesToFloating(split.fixedLon),
                VertexStore.fixedDegreesToFloating(split.fixedLat)));
        ArrayNode edges = featureCollection(out, "edges");
        for (Piece p : pieces) {
            ObjectNode properties = edgeProperties(p.edgeIndex);
            if (!(p.wholeEdge && p.complete)) properties.put("length", round(p.meters, 3));
            addFeature(edges, OswDemoServer.lineJson(p.geometry), properties);
        }
        ArrayNode nodeCosts = featureCollection(out, "node_costs");
        VertexStore.Vertex vertex = streets.vertexStore.getCursor();
        router.getReachedVertices().forEachEntry((v, seconds) -> {
            if (seconds <= limit) {
                vertex.seek(v);
                ObjectNode properties = MAPPER.createObjectNode();
                properties.put("cost", seconds - offStreet);
                addFeature(nodeCosts, point(vertex.getLon(), vertex.getLat()).get("geometry"), properties);
            }
            return true;
        });
        return out;
    }

    // ------------------------------------------------------------------------------------------------ Shortest path

    /** A walk along part of a directed edge, as one step of a route. */
    private static class Step {
        final int edgeIndex;
        final double from, to, meters;
        final int seconds;

        Step (int edgeIndex, double from, double to, double meters, int seconds) {
            this.edgeIndex = edgeIndex;
            this.from = from;
            this.to = to;
            this.meters = meters;
            this.seconds = seconds;
        }
    }

    private ObjectNode shortestPath (Request req) throws IOException {
        double lon1 = number(req, "lon1"), lat1 = number(req, "lat1");
        double lon2 = number(req, "lon2"), lat2 = number(req, "lat2");
        StreetRouter router = router(spec(req));
        Split origin = snap(router, lat1, lon1), destination = snap(router, lat2, lon2);
        if (origin == null || destination == null) return code("InvalidWaypoint");
        router.setOrigin(origin, lat1, lon1);
        router.setDestination(destination);
        router.route();
        List<Step> steps = steps(router);
        if (steps == null || steps.isEmpty()) return code("NoPath");

        ObjectNode out = code("Ok");
        out.set("origin", point(lon1, lat1));
        out.set("destination", point(lon2, lat2));
        out.putArray("waypoints").add(point(lon1, lat1)).add(point(lon2, lat2));
        ObjectNode route = out.putArray("routes").addObject();
        ObjectNode geometry = route.putObject("geometry");
        geometry.put("type", "LineString");
        ArrayNode track = geometry.putArray("coordinates");
        ArrayNode segments = featureCollection(route, "segments");
        ArrayNode leg = route.putArray("legs").addArray();
        double meters = 0;
        int seconds = 0;
        Coordinate last = null;
        EdgeStore.Edge edge = streets.edgeStore.getCursor();
        for (Step step : steps) {
            edge.seek(step.edgeIndex);
            LineString line = step.from == 0 && step.to == 1 ? edge.getGeometry()
                    : new GroundLine(edge.getGeometry()).extract(step.from, step.to);
            ObjectNode properties = edgeProperties(step.edgeIndex);
            properties.put("length", round(step.meters, 3));
            properties.put("cost", step.seconds);
            ObjectNode lineJson = OswDemoServer.lineJson(line);
            addFeature(segments, lineJson, properties);
            addFeature(leg, lineJson.deepCopy(), properties.deepCopy());
            for (Coordinate c : line.getCoordinates()) {
                if (last == null || !c.equals2D(last, 1e-9)) track.addArray().add(round(c.x, 7)).add(round(c.y, 7));
                last = c;
            }
            meters += step.meters;
            seconds += step.seconds;
        }
        route.put("distance", round(meters, 3));
        route.put("duration", seconds);
        route.put("total_cost", seconds);
        return out;
    }

    /**
     * The route found by a finished search from its origin to its destination, as the edges walked in order, or null
     * if there is none. Times leave out what R5 allows for getting from the requested point to the network.
     */
    private List<Step> steps (StreetRouter router) {
        Split origin = router.getOriginSplit(), destination = router.getDestinationSplit();
        double atOrigin = server.splitFraction(origin), atDestination = server.splitFraction(destination);
        int offStreet = OswDemoServer.offStreetSeconds(origin, BASE_SPEED);
        StreetRouter.State end = router.getState(destination);

        // The router only leaves the origin's edge by one of its ends. If the destination is on the same edge, walking
        // straight to it may be quicker, or the only way.
        if (origin.edge == destination.edge) {
            boolean forward = destination.distance0_mm >= origin.distance0_mm;
            EdgeStore.Edge edge = streets.edgeStore.getCursor(forward ? origin.edge : origin.edge + 1);
            double meters = Math.abs(destination.distance0_mm - origin.distance0_mm) / 1000d;
            int direct = PedestrianCostTimeCalculator.roundPartialSeconds(
                    router.getPedestrianCostTable().partialSeconds(edge, BASE_SPEED, meters));
            if (direct >= 0 && (end == null || direct <= end.getDurationSeconds() - offStreet)) {
                double from = forward ? atOrigin : 1 - atOrigin, to = forward ? atDestination : 1 - atDestination;
                return List.of(new Step(edge.getEdgeIndex(), from, Math.max(from, to), meters, direct));
            }
        }
        if (end == null) return null;

        List<StreetRouter.State> states = new ArrayList<>();
        for (StreetRouter.State s = end; s != null; s = s.backState) states.add(s);
        Collections.reverse(states);
        List<Step> steps = new ArrayList<>();
        int before = offStreet;
        for (int i = 0; i < states.size(); i++) {
            StreetRouter.State state = states.get(i);
            boolean forward = state.backEdge % 2 == 0;
            int seconds = state.getDurationSeconds() - before;
            before = state.getDurationSeconds();
            if (i == 0) {
                // From the origin, partway along its edge, to one end of it.
                double from = forward ? atOrigin : 1 - atOrigin;
                double meters = (forward ? origin.distance1_mm : origin.distance0_mm) / 1000d;
                if (meters >= PedestrianCostTable.AT_END_METERS) {
                    steps.add(new Step(state.backEdge, from, 1, meters, seconds));
                }
            } else if (i == states.size() - 1) {
                // From one end of the destination's edge to the destination, partway along it.
                double to = forward ? atDestination : 1 - atDestination;
                double meters = (forward ? destination.distance0_mm : destination.distance1_mm) / 1000d;
                if (meters >= PedestrianCostTable.AT_END_METERS) {
                    steps.add(new Step(state.backEdge, 0, to, meters, seconds));
                }
            } else {
                steps.add(new Step(state.backEdge, 0, 1, edgeLength(state.backEdge), seconds));
            }
        }
        return steps;
    }

    // ------------------------------------------------------------------------------------------------ Output

    private double edgeLength (int edgeIndex) {
        double length = attrs.lengthMeters(edgeIndex);
        return Double.isNaN(length) ? streets.edgeStore.getCursor(edgeIndex).getLengthM() : length;
    }

    /**
     * The OSW properties of an edge as Walksheds reports them. _u and _v are always the ends of the OSW edge, whichever
     * way it is walked; incline is in the direction of the given directed edge.
     */
    private ObjectNode edgeProperties (int edgeIndex) {
        ObjectNode properties = MAPPER.createObjectNode();
        EdgeStore.Edge pair = streets.edgeStore.getCursor(edgeIndex & ~1);
        VertexStore.Vertex vertex = streets.vertexStore.getCursor();
        properties.put("_id", attrs.ids.edgeId(pair.getOSMID()));
        int[] ends = {pair.getFromVertex(), pair.getToVertex()};
        String[] names = {"_u", "_v"};
        for (int i = 0; i < 2; i++) {
            String nodeId = attrs.oswNodeId(ends[i]);
            if (nodeId != null) properties.put(names[i] + "_id", nodeId);
            vertex.seek(ends[i]);
            properties.put(names[i], vertex.getLon() + ", " + vertex.getLat());
        }
        properties.put("length", round(edgeLength(edgeIndex), 3));
        double incline = attrs.incline(edgeIndex);
        if (!Double.isNaN(incline)) properties.put("incline", incline);
        Map<String, String> tags = attrs.tags(edgeIndex);
        if (tags != null) tags.forEach((key, value) -> properties.put(key.replace(':', '_'), value));
        return properties;
    }

    private static ArrayNode featureCollection (ObjectNode parent, String name) {
        ObjectNode collection = parent.putObject(name);
        collection.put("type", "FeatureCollection");
        return collection.putArray("features");
    }

    private static void addFeature (ArrayNode features, JsonNode geometry, ObjectNode properties) {
        ObjectNode feature = features.addObject();
        feature.put("type", "Feature");
        feature.set("geometry", geometry);
        feature.set("properties", properties);
    }

    private static ObjectNode point (double lon, double lat) {
        ObjectNode feature = MAPPER.createObjectNode();
        feature.put("type", "Feature");
        ObjectNode geometry = feature.putObject("geometry");
        geometry.put("type", "Point");
        geometry.putArray("coordinates").add(round(lon, 7)).add(round(lat, 7));
        feature.putObject("properties");
        return feature;
    }

    private static double round (double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }
}
