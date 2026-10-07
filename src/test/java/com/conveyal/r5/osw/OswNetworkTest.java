package com.conveyal.r5.osw;

import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.cluster.TransportNetworkConfig;
import com.conveyal.r5.kryo.KryoNetworkSerializer;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.PointSetTimes;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.map.TIntIntMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds a network from the synthetic OSW fixture (osw-tools/verification/make_test_fixture.py describes its layout)
 * and checks OSW reading, attribute capture, and routing with a pedestrian cost profile.
 */
public class OswNetworkTest {

    private static TransportNetwork network;
    private static StreetLayer streets;
    private static OswEdgeAttributes attrs;
    private static PedestrianCostProfile profile;

    @BeforeAll
    public static void buildNetwork () throws Exception {
        File dir = new File(OswNetworkTest.class.getResource("tiny").toURI());
        TransportNetworkConfig config = new TransportNetworkConfig();
        config.pruneIslands = false;
        network = TransportNetwork.fromOsw(dir.getAbsolutePath(), config);
        streets = network.streetLayer;
        attrs = streets.edgeStore.oswAttributes;
        profile = PedestrianCostProfileTest.loadProfile();
    }

    @Test
    public void eachOswEdgeBecomesOneEdgePair () {
        assertNotNull(attrs);
        // 16 OSW edges, no transit: exactly 16 pairs, one per OSW edge, with OSW lengths.
        Map<String, Integer> pairsByOswEdge = new HashMap<>();
        EdgeStore.Edge e = streets.edgeStore.getCursor();
        for (int i = 0; i < streets.edgeStore.nEdges(); i += 2) {
            e.seek(i);
            pairsByOswEdge.merge(attrs.ids.edgeId(e.getOSMID()), 1, Integer::sum);
        }
        assertEquals(16, pairsByOswEdge.size());
        pairsByOswEdge.values().forEach(n -> assertEquals(1, n));
        int e104 = forwardEdge("104");
        e.seek(e104);
        assertEquals(100.02, e.getLengthM(), 0.001);
        assertEquals("12", attrs.oswNodeId(e.getFromVertex()));
        assertEquals("13", attrs.oswNodeId(e.getToVertex()));
    }

    @Test
    public void attributesAreCaptured () {
        int e102 = forwardEdge("102");
        assertEquals(0.10, attrs.incline(e102), 1e-12);
        assertEquals(-0.10, attrs.incline(e102 + 1), 1e-12);
        assertEquals("sidewalk", attrs.tags(e102).get("footway"));
        assertFalse(attrs.tags(e102).containsKey("_id"));
        // Steps have a non-numeric incline, treated as unknown.
        assertTrue(Double.isNaN(attrs.incline(forwardEdge("114"))));
        // Curb ramps from kerb nodes: lowered at both ends, raised at one end, and no kerbs (so nothing in the way).
        assertEquals(OswEdgeAttributes.CURB_RAMPS_YES, attrs.curbRamps(forwardEdge("108")));
        assertEquals(OswEdgeAttributes.CURB_RAMPS_NO, attrs.curbRamps(forwardEdge("111")));
        assertEquals(OswEdgeAttributes.CURB_RAMPS_YES, attrs.curbRamps(forwardEdge("112")));
    }

    @Test
    public void nonNumericNodeIdsArePreserved () {
        boolean found = false;
        for (int v : attrs.nodeIdForVertex.keys()) {
            if ("n-west".equals(attrs.oswNodeId(v))) found = true;
        }
        assertTrue(found);
    }

    @Test
    public void routingAppliesTheProfile () {
        // Default parameters: uphill 8.5%, so 2 -> 3 (10% up) is impassable but 3 -> 2 (10% down) is fine.
        Map<String, Integer> fromS2 = route("2", Map.of(), 1.3f);
        assertFalse(fromS2.containsKey("3"));
        assertTrue(fromS2.containsKey("1"));
        // Crossing 108 has curb ramps: the north side is reachable. Crossing 111 (raised kerb) is not usable, but
        // its north end (32) is reachable along the north sidewalk.
        assertTrue(fromS2.containsKey("12"));
        assertTrue(fromS2.containsKey("32"));
        assertFalse(fromS2.containsKey("31"));
        // Elevator and the short steep segment are usable; steps, generic footways and roads are not.
        assertTrue(fromS2.containsKey("41"));
        assertTrue(fromS2.containsKey("14"));
        assertFalse(fromS2.containsKey("51"));
        assertFalse(fromS2.containsKey("n-west"));
        assertFalse(fromS2.containsKey("61"));

        Map<String, Integer> fromS3 = route("3", Map.of(), 1.3f);
        assertTrue(fromS3.containsKey("2"));

        // Without curb avoidance, the raised-kerb and unramped crossings are usable.
        Map<String, Integer> noCurbs = route("2", Map.of("avoid_curbs", false), 1.3f);
        assertTrue(noCurbs.containsKey("31"));
    }

    @Test
    public void routerCostsAreExactCostsRoundedUpPerEdge () {
        PedestrianCostSpec spec = profile.resolve(Map.of());
        int origin = vertex("2");
        double[] exact = OswWalkshedMain.exactDijkstra(streets, spec, origin, 1.3, 3600);
        Map<String, Integer> routed = route("2", Map.of(), 1.3f);
        for (Map.Entry<String, Integer> r : routed.entrySet()) {
            double e = exact[vertex(r.getKey())];
            // Each edge is rounded up to a whole second, so the router is never faster and the difference is at
            // most one second per edge on the path (paths in this fixture have at most 6 edges).
            assertTrue(r.getValue() >= e - 1e-9, r.getKey());
            assertTrue(r.getValue() <= e + 6, r.getKey());
        }
        // 2 -> 21 -> 22 -> 12 -> 13: three short sidewalks and a crossing with its 30 s delay, then 100 m.
        assertEquals(exact[vertex("13")], 2.0 / 1.3 / factor(spec, 0.01) + 15.98 / 1.3 + 30
                + 2.0 / 1.3 / factor(spec, -0.01) + 100.02 / 1.3 / factor(spec, 0), 1e-9);
    }

    @Test
    public void oswAttributesSurviveSerialization () throws Exception {
        File file = File.createTempFile("osw-network", ".dat");
        file.deleteOnExit();
        KryoNetworkSerializer.write(network, file);
        TransportNetwork copy = KryoNetworkSerializer.read(file);
        OswEdgeAttributes copied = copy.streetLayer.edgeStore.oswAttributes;
        int e102 = forwardEdge("102");
        assertEquals(attrs.tags(e102), copied.tags(e102));
        assertEquals(attrs.incline(e102 + 1), copied.incline(e102 + 1));
        assertEquals(attrs.curbRamps(forwardEdge("108")), copied.curbRamps(forwardEdge("108")));
        int v = vertex("n-west");
        assertEquals("n-west", copied.oswNodeId(v));
        PedestrianCostSpec spec = profile.resolve(Map.of());
        EdgeStore.Edge edge = copy.streetLayer.edgeStore.getCursor(e102);
        assertEquals(
                attrs.costTable(spec, streets.edgeStore).seconds(streets.edgeStore.getCursor(e102), 1.3),
                copied.costTable(spec, copy.streetLayer.edgeStore).seconds(edge, 1.3), 1e-12);
    }

    /**
     * Steps in TDEI data may end on a node of their own that sits exactly on a sidewalk's node. Edges are joined by
     * where they end, as in Unweaver, so the two count as one node, and it keeps a kerb type only one of them has.
     */
    @Test
    public void nodesAtTheSamePositionAreJoined () throws Exception {
        File dir = Files.createTempDirectory("osw-coincident").toFile();
        dir.deleteOnExit();
        Files.writeString(new File(dir, "t.nodes.geojson").toPath(), "{\"type\": \"FeatureCollection\", \"features\": ["
                + node("a", -122.3000, 47.65, "") + ","
                + node("b", -122.2990, 47.65, "") + ","
                + node("b-again", -122.2990, 47.65, ", \"barrier\": \"kerb\", \"kerb\": \"raised\"") + ","
                + node("c", -122.2980, 47.65, "") + "]}");
        Files.writeString(new File(dir, "t.edges.geojson").toPath(), "{\"type\": \"FeatureCollection\", \"features\": ["
                + edge("e1", "a", "b", -122.3000, -122.2990, "sidewalk") + ","
                + edge("e2", "b-again", "c", -122.2990, -122.2980, "crossing") + "]}");
        TransportNetworkConfig config = new TransportNetworkConfig();
        config.pruneIslands = false;
        TransportNetwork joined = TransportNetwork.fromOsw(dir.getAbsolutePath(), config);
        OswEdgeAttributes joinedAttrs = joined.streetLayer.edgeStore.oswAttributes;
        Map<String, Integer> vertices = new HashMap<>();
        for (int v : joinedAttrs.nodeIdForVertex.keys()) vertices.put(joinedAttrs.oswNodeId(v), v);
        // One vertex stands for both nodes, under the ID of the first.
        assertEquals(3, vertices.size());
        assertFalse(vertices.containsKey("b-again"));

        StreetRouter router = new StreetRouter(joined.streetLayer);
        router.profileRequest = new ProfileRequest();
        router.streetMode = StreetMode.WALK;
        router.timeLimitSeconds = 3600;
        router.setOrigin(vertices.get("a"));
        router.route();
        assertTrue(router.getReachedVertices().containsKey(vertices.get("c")));

        // The crossing's first end is the joined node, which took the second node's raised kerb: no curb ramps.
        EdgeStore.Edge e = joined.streetLayer.edgeStore.getCursor();
        for (int i = 0; i < joined.streetLayer.edgeStore.nEdges(); i += 2) {
            e.seek(i);
            if ("e2".equals(joinedAttrs.ids.edgeId(e.getOSMID()))) {
                assertEquals(OswEdgeAttributes.CURB_RAMPS_NO, joinedAttrs.curbRamps(i));
            }
        }
    }

    private static String node (String id, double lon, double lat, String moreProperties) {
        return String.format("{\"type\": \"Feature\", \"geometry\": {\"type\": \"Point\", \"coordinates\": [%s, %s]}, "
                + "\"properties\": {\"_id\": \"%s\"%s}}", lon, lat, id, moreProperties);
    }

    private static String edge (String id, String u, String v, double lon0, double lon1, String footway) {
        return String.format("{\"type\": \"Feature\", \"geometry\": {\"type\": \"LineString\", "
                + "\"coordinates\": [[%s, 47.65], [%s, 47.65]]}, \"properties\": {\"_id\": \"%s\", \"_u_id\": \"%s\", "
                + "\"_v_id\": \"%s\", \"highway\": \"footway\", \"footway\": \"%s\"}}", lon0, lon1, id, u, v, footway);
    }

    @Test
    public void profileOnNonOswNetworkIsAnError () {
        StreetLayer plain = new StreetLayer();
        plain.edgeStore.oswAttributes = null;
        StreetRouter router = new StreetRouter(plain);
        router.profileRequest.pedestrianCost = new PedestrianCostRequest(profile.source, Map.of());
        assertThrows(IllegalArgumentException.class, router::route);
    }

    private static double factor (PedestrianCostSpec spec, double incline) {
        double[] out = new double[2];
        spec.evaluate(Map.of("footway", "sidewalk"), incline, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        return out[0];
    }

    @Test
    public void destinationsAlongImpassableEdgesAreNotReached () {
        // A point beside the middle of edge 102 (2 -> 3 is 10% uphill, beyond the default 8.5% limit).
        VertexStore.Vertex a = streets.vertexStore.getCursor(vertex("2"));
        VertexStore.Vertex b = streets.vertexStore.getCursor(vertex("3"));
        Coordinate mid = new Coordinate((a.getLon() + b.getLon()) / 2, (a.getLat() + b.getLat()) / 2 - 0.00001);
        LinkedPointSet linked = new LinkedPointSet(new FreeFormPointSet(mid), streets, StreetMode.WALK, null);

        // From 2, the only way to the point is uphill along 102: unreachable with the profile, reachable without.
        StreetRouter fromS2 = router("2", Map.of(), 1.3f);
        PointSetTimes withProfile = linked.eval(fromS2::getTravelTimeToVertex, 1300, 1300, null,
                fromS2.getPedestrianCostTable(), 1.3);
        assertEquals(Integer.MAX_VALUE, withProfile.getTravelTimeToPoint(0));
        PointSetTimes plain = linked.eval(fromS2::getTravelTimeToVertex, 1300, 1300, null);
        assertTrue(plain.getTravelTimeToPoint(0) < Integer.MAX_VALUE);

        // From 3 it is downhill, and slower than walking flat ground at the same speed.
        StreetRouter fromS3 = router("3", Map.of(), 1.3f);
        int downhill = linked.eval(fromS3::getTravelTimeToVertex, 1300, 1300, null,
                fromS3.getPedestrianCostTable(), 1.3).getTravelTimeToPoint(0);
        int flat = linked.eval(fromS3::getTravelTimeToVertex, 1300, 1300, null).getTravelTimeToPoint(0);
        assertTrue(downhill > flat, downhill + " vs " + flat);
    }

    @Test
    public void reusedRouterUsesTheLatestProfile () {
        StreetRouter r = router("2", Map.of(), 1.3f);
        assertEquals(Integer.MAX_VALUE, r.getTravelTimeToVertex(vertex("31")));
        r.profileRequest.pedestrianCost = new PedestrianCostRequest(profile.source, Map.of("avoid_curbs", false));
        r.setOrigin(vertex("2"));
        r.route();
        assertTrue(r.getTravelTimeToVertex(vertex("31")) < Integer.MAX_VALUE);
    }

    @Test
    public void scenarioCopiesKeepOswNodeIds () {
        EdgeStore copy = streets.edgeStore.extendOnlyCopy(streets);
        assertEquals("n-west", copy.oswAttributes.oswNodeId(vertex("n-west")));
    }

    private static Map<String, Integer> route (String originNode, Map<String, Object> params, float walkSpeed) {
        StreetRouter router = router(originNode, params, walkSpeed);
        TIntIntMap reached = router.getReachedVertices();
        Map<String, Integer> result = new HashMap<>();
        reached.forEachEntry((v, t) -> {
            String id = attrs.oswNodeId(v);
            if (id != null && !id.equals(originNode)) result.put(id, t);
            return true;
        });
        return result;
    }

    private static StreetRouter router (String originNode, Map<String, Object> params, float walkSpeed) {
        ProfileRequest req = new ProfileRequest();
        req.walkSpeed = walkSpeed;
        req.pedestrianCost = new PedestrianCostRequest(profile.source, params);
        StreetRouter router = new StreetRouter(streets);
        router.profileRequest = req;
        router.streetMode = StreetMode.WALK;
        router.timeLimitSeconds = 3600;
        router.setOrigin(vertex(originNode));
        router.route();
        return router;
    }

    private static int vertex (String oswNodeId) {
        for (int v : attrs.nodeIdForVertex.keys()) {
            if (oswNodeId.equals(attrs.oswNodeId(v))) return v;
        }
        throw new IllegalArgumentException(oswNodeId);
    }

    /** @return the forward edge of the pair made from the given OSW edge. */
    private static int forwardEdge (String oswEdgeId) {
        EdgeStore.Edge e = streets.edgeStore.getCursor();
        for (int i = 0; i < streets.edgeStore.nEdges(); i += 2) {
            e.seek(i);
            if (oswEdgeId.equals(attrs.ids.edgeId(e.getOSMID()))) return i;
        }
        throw new IllegalArgumentException(oswEdgeId);
    }
}
