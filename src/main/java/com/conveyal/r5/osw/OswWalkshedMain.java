package com.conveyal.r5.osw;

import com.conveyal.r5.analyst.cluster.TransportNetworkConfig;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.map.TIntIntMap;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Computes a walkshed (shortest path tree) from one OSW node on a network built from OpenSidewalks data, for
 * validating R5's pedestrian cost profiles against Unweaver run with the same profile (osw-tools/).
 *
 * Two results are written:
 *  - "exact": a plain Dijkstra over R5's street graph using double-precision edge costs and the OSW edge lengths,
 *    with no rounding. This isolates the cost function and OSW reading from R5's router, and should match Unweaver's
 *    shortest_path_tree node costs to floating point tolerance.
 *  - "router": R5's actual StreetRouter with the profile applied. Costs are whole seconds, each edge rounded up,
 *    and edge lengths are stored to the millimeter, so these differ slightly from the exact costs.
 *
 * Every output carries provenance: profileId, runSpecId, parameter values, a hash of the OSW input, and R5 version.
 *
 * Usage:
 *   OswWalkshedMain --osw DATASET --profile PROFILE.json [--params '{"uphill":0.083}'] [--walk-speed 1.3]
 *                   (--origin-node OSW_NODE_ID | --origin-lonlat LON,LAT) --max-cost SECONDS --out OUT.json
 */
public class OswWalkshedMain {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static void main (String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        String oswPath = require(opts, "osw");
        PedestrianCostProfile profile = PedestrianCostProfile.fromFile(new File(require(opts, "profile")));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = opts.containsKey("params")
                ? MAPPER.readValue(opts.get("params"), Map.class) : new HashMap<>();
        double walkSpeed = Double.parseDouble(opts.getOrDefault("walk-speed", "1.3"));
        double maxCost = Double.parseDouble(require(opts, "max-cost"));

        TransportNetworkConfig config = new TransportNetworkConfig();
        // Unweaver keeps every component of the graph, so don't prune islands.
        config.pruneIslands = Boolean.parseBoolean(opts.getOrDefault("prune-islands", "false"));
        TransportNetwork network = TransportNetwork.fromOsw(oswPath, config);
        StreetLayer streets = network.streetLayer;
        OswEdgeAttributes attrs = streets.edgeStore.oswAttributes;

        int origin = findOrigin(streets, attrs, opts);
        PedestrianCostRequest costRequest = new PedestrianCostRequest(profile.source, params);
        PedestrianCostSpec spec = costRequest.spec();

        ObjectNode out = MAPPER.createObjectNode();
        ObjectNode provenance = out.putObject("provenance");
        spec.provenance().forEach(provenance::put);
        provenance.put("baseSpeed", walkSpeed);
        provenance.put("networkInputMd5", md5OfInput(new File(oswPath)));
        provenance.put("engine", "r5");
        ObjectNode query = out.putObject("query");
        query.put("originNode", attrs.oswNodeId(origin));
        VertexStore.Vertex v = streets.vertexStore.getCursor(origin);
        query.put("lon", v.getLon());
        query.put("lat", v.getLat());
        query.put("maxCost", maxCost);
        query.set("parameters", MAPPER.valueToTree(spec.parameterValues));

        double[] exact = exactDijkstra(streets, spec, origin, walkSpeed, maxCost);
        out.set("exact", nodeCosts(streets, attrs, exact));
        out.set("exactReachableEdges", reachableEdges(streets, attrs, spec, exact, walkSpeed, maxCost, false));
        out.set("exactPartialEdges", reachableEdges(streets, attrs, spec, exact, walkSpeed, maxCost, true));

        ProfileRequest req = new ProfileRequest();
        req.walkSpeed = (float) walkSpeed;
        req.pedestrianCost = costRequest;
        StreetRouter router = new StreetRouter(streets);
        router.profileRequest = req;
        router.streetMode = StreetMode.WALK;
        router.timeLimitSeconds = (int) Math.ceil(maxCost);
        router.setOrigin(origin);
        router.route();
        TIntIntMap reached = router.getReachedVertices();
        double[] routed = new double[streets.vertexStore.getVertexCount()];
        Arrays.fill(routed, Double.POSITIVE_INFINITY);
        routed[origin] = 0;
        // getReachedVertices() only reports vertices at the end of traversed edges, so the origin can appear with the
        // cost of a round trip. Keep the minimum.
        reached.forEachEntry((vertex, seconds) -> {
            if (seconds <= maxCost && seconds < routed[vertex]) routed[vertex] = seconds;
            return true;
        });
        out.set("router", nodeCosts(streets, attrs, routed));

        File outFile = new File(require(opts, "out"));
        MAPPER.writeValue(outFile, out);
        System.out.printf("Origin %s: %d nodes reached (exact), %d (router). Wrote %s%n", attrs.oswNodeId(origin),
                out.get("exact").size(), out.get("router").size(), outFile);
        System.out.println("profileId " + spec.profile.profileId + "  runSpecId " + spec.runSpecId);
    }

    /** Dijkstra with unrounded double costs, using OSW lengths where present. Unreached vertices are +infinity. */
    static double[] exactDijkstra (StreetLayer streets, PedestrianCostSpec spec, int origin, double walkSpeed,
                                   double maxCost) {
        return exactDijkstra(streets, spec, Map.of(origin, 0.0), walkSpeed, maxCost, false);
    }

    /**
     * As {@link #exactDijkstra(StreetLayer, PedestrianCostSpec, int, double, double)}, but starting from several
     * vertices at once, each with a cost already incurred, and optionally costing every edge by its incline as mapped
     * whichever way it is walked. R5 does not route like that: walked backwards, uphill is downhill. Walksheds does,
     * and this allows its costs to be reproduced exactly when checking R5 against it (see WalkshedsRegressionTest).
     */
    static double[] exactDijkstra (StreetLayer streets, PedestrianCostSpec spec, Map<Integer, Double> startCosts,
                                   double walkSpeed, double maxCost, boolean inclineAsMapped) {
        OswEdgeAttributes attrs = streets.edgeStore.oswAttributes;
        double[] cost = new double[streets.vertexStore.getVertexCount()];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        startCosts.forEach((vertex, incurred) -> {
            cost[vertex] = incurred;
            queue.add(new double[] {incurred, vertex});
        });
        EdgeStore.Edge edge = streets.edgeStore.getCursor();
        double[] eval = new double[2];
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int vertex = (int) top[1];
            if (top[0] > cost[vertex]) continue;
            for (TIntIterator it = streets.outgoingEdges.get(vertex).iterator(); it.hasNext(); ) {
                int e = it.next();
                edge.seek(e);
                if (!edge.getFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN)) continue;
                double seconds = exactSeconds(edge, attrs, spec, walkSpeed, eval, inclineAsMapped);
                if (Double.isNaN(seconds)) continue;
                double c = top[0] + seconds;
                int to = edge.getToVertex();
                if (c <= maxCost && c < cost[to]) {
                    cost[to] = c;
                    queue.add(new double[] {c, to});
                }
            }
        }
        return cost;
    }

    private static double exactSeconds (EdgeStore.Edge edge, OswEdgeAttributes attrs, PedestrianCostSpec spec,
                                        double walkSpeed, double[] eval, boolean inclineAsMapped) {
        int e = edge.getEdgeIndex();
        double oswLength = attrs.lengthMeters(e);
        double length = Double.isNaN(oswLength) ? edge.getLengthM() : oswLength;
        // The forward edge of each pair is the even one, and carries the incline as mapped.
        double incline = attrs.incline(inclineAsMapped ? e & ~1 : e);
        spec.evaluate(attrs.tags(e), incline, length, attrs.curbRamps(e), eval);
        if (Double.isNaN(eval[0])) return Double.NaN;
        return PedestrianCostSpec.seconds(length, walkSpeed, eval[0], eval[1]);
    }

    private static ArrayNode nodeCosts (StreetLayer streets, OswEdgeAttributes attrs, double[] cost) {
        ArrayNode nodes = MAPPER.createArrayNode();
        VertexStore.Vertex v = streets.vertexStore.getCursor();
        for (int i = 0; i < cost.length; i++) {
            if (Double.isInfinite(cost[i])) continue;
            String id = attrs.oswNodeId(i);
            if (id == null) continue; // Not an OSW node (e.g. a split vertex)
            v.seek(i);
            ObjectNode n = nodes.addObject();
            n.put("node", id);
            n.put("lon", v.getLon());
            n.put("lat", v.getLat());
            n.put("cost", cost[i]);
        }
        return nodes;
    }

    /**
     * Directed edges fully traversable within maxCost from the origin, as (edge, u, v, layer). With partial=true,
     * instead the passable edges whose start is reached but which cannot be traversed completely within maxCost: the
     * walkshed boundary. Unweaver's reachable_tree includes every passable edge leaving a reached node, either whole
     * or as a partial edge, so its edges correspond to the union of both lists.
     */
    private static ArrayNode reachableEdges (StreetLayer streets, OswEdgeAttributes attrs, PedestrianCostSpec spec,
                                             double[] cost, double walkSpeed, double maxCost, boolean partial) {
        ArrayNode edges = MAPPER.createArrayNode();
        EdgeStore.Edge edge = streets.edgeStore.getCursor();
        double[] eval = new double[2];
        for (int e = 0; e < streets.edgeStore.nEdges(); e++) {
            edge.seek(e);
            int from = edge.getFromVertex();
            if (Double.isInfinite(cost[from]) || !edge.getFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN)) continue;
            double seconds = exactSeconds(edge, attrs, spec, walkSpeed, eval, false);
            if (Double.isNaN(seconds) || (cost[from] + seconds > maxCost) != partial) continue;
            ObjectNode n = edges.addObject();
            n.put("edge", attrs.ids.edgeId(edge.getOSMID()));
            n.put("u", attrs.oswNodeId(from));
            n.put("v", attrs.oswNodeId(edge.getToVertex()));
            n.put("layer", spec.layerName(attrs.tags(e)));
        }
        return edges;
    }

    private static int findOrigin (StreetLayer streets, OswEdgeAttributes attrs, Map<String, String> opts) {
        if (opts.containsKey("origin-node")) {
            String wanted = opts.get("origin-node");
            int[] found = {-1};
            attrs.nodeIdForVertex.forEachEntry((vertex, id) -> {
                if (attrs.ids.nodeId(id).equals(wanted)) {
                    found[0] = vertex;
                    return false;
                }
                return true;
            });
            if (found[0] < 0) throw new IllegalArgumentException("No street vertex for OSW node " + wanted);
            return found[0];
        }
        String[] lonLat = require(opts, "origin-lonlat").split(",");
        double lon = Double.parseDouble(lonLat[0]), lat = Double.parseDouble(lonLat[1]);
        // Nearest OSW node, so the origin is a node as in Unweaver when queried at a node's coordinates.
        VertexStore.Vertex v = streets.vertexStore.getCursor();
        int best = -1;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int vertex : attrs.nodeIdForVertex.keys()) {
            v.seek(vertex);
            double d = Math.hypot((v.getLon() - lon) * Math.cos(Math.toRadians(lat)), v.getLat() - lat) * 111320;
            if (d < bestDist) {
                bestDist = d;
                best = vertex;
            }
        }
        System.out.printf("Origin snapped to OSW node %s, %.2f m away.%n", attrs.oswNodeId(best), bestDist);
        return best;
    }

    /** md5 of the OSW input file(s), so results record exactly which data they were computed on. */
    static String md5OfInput (File path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            List<File> files = new ArrayList<>();
            if (path.isDirectory()) {
                File[] children = path.listFiles((d, n) -> n.endsWith(".geojson"));
                if (children != null) files.addAll(Arrays.asList(children));
                files.sort((a, b) -> a.getName().compareTo(b.getName()));
            } else {
                files.add(path);
            }
            for (File f : files) md.update(Files.readAllBytes(f.toPath()));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, String> parseArgs (String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Unexpected argument " + args[i]);
            String key = args[i].substring(2);
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + args[i]);
            opts.put(key, args[++i]);
        }
        return opts;
    }

    private static String require (Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Missing --" + key + ". See OswWalkshedMain Javadoc for usage.");
        }
        return v;
    }
}
