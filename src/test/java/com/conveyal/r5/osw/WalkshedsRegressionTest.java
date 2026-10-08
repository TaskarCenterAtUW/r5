package com.conveyal.r5.osw;

import com.conveyal.r5.analyst.cluster.TransportNetworkConfig;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import spark.Service;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks R5 against output saved from the TDEI Walksheds service, so that R5 does not drift from it unnoticed.
 *
 * R5 is not meant to match Walksheds in every respect. osw-tools/DIFFERENCES.md lists the differences that are
 * accepted, of which one can affect any result: where a requested point attaches to the network. In compatibility
 * mode R5 attaches where Walksheds would, except where two edges are equally near, between which Walksheds' choice
 * cannot be predicted.
 *
 * So there are two kinds of test here.
 *
 * The exact tests take that out of the comparison, and then expect R5 to agree with Walksheds to within a second at
 * every node. They cost the network with R5's own profile, attributes and connections, by a search of their own that
 * starts from Walksheds' costs at the two ends of the edge its origin is on. Any difference they find is one that
 * has not been accepted: a rule of the cost function, how curb ramps are decided, which edges join, what a profile
 * parameter does.
 *
 * The end-to-end tests ask R5's Walksheds-compatible API the same question Walksheds was asked, with nothing taken
 * out. They check what the exact tests cannot: that the router and the API give the answer the cost model implies,
 * from where the point attaches to the unrounded cost at each node.
 *
 * The fixtures come from the TDEI quality reports' test data (osw-tools/verification/make_walksheds_fixtures.py):
 * "latah", a small dataset without roads, with Walksheds' answer for three points under each of the reports' four
 * profiles; and "newcastle", part of a dataset with roads, with one answer in which streets are walked at a penalty.
 */
public class WalkshedsRegressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The speed Walksheds costs at, in meters per second. */
    private static final double BASE_SPEED = 1.3;

    /**
     * How far a node's cost may be from Walksheds', in seconds, in the exact tests. Not zero, because Walksheds works
     * from edge lengths that are not rounded to the centimeter as the dataset's are. Over the fixtures the largest
     * difference is under half a second.
     */
    private static final double EXACT_TOLERANCE_SECONDS = 1.0;

    /**
     * How far a node's cost may be from Walksheds' in the end-to-end tests: END_TO_END_SECONDS plus END_TO_END_SHARE
     * of the cost. The router keeps fractions of a second, so this allows only for the lengths Walksheds works from,
     * as in the exact tests, and for the place a point attaches being stored to the centimeter.
     */
    private static final double END_TO_END_SECONDS = 1;
    private static final double END_TO_END_SHARE = 0.005;

    /**
     * In the end-to-end tests, a node that one engine reaches at less than this share of the cost limit must be
     * reached by the other too. Nearer the limit, the cost allowance above can put a node on either side of it.
     */
    private static final double END_TO_END_REACH_SHARE = 0.99;

    /**
     * Walksheds answers whose costs the end-to-end tests do not compare, by what their file names contain. Latah's
     * third point is nearest to a node where two crossings meet, so both are equally near. Which of the two Walksheds
     * attaches to cannot be predicted, and here it is not the one R5 takes (the first in the dataset), which puts
     * R5's start 8 m along a crossing instead of at the node. The exact tests still cover these answers.
     */
    private static final List<String> START_ON_TIED_EDGES = List.of("latah/walksheds/poi_3_");

    private static final Map<String, Fixture> FIXTURES = new LinkedHashMap<>();

    /** One dataset, the network R5 builds from it, a server on it, and the Walksheds answers saved for it. */
    private static class Fixture {
        File dir;
        StreetLayer streets;
        JsonNode cases;
        Service http;
        /** The vertex at each position, keyed by longitude and latitude in units of 1e-7 degrees. */
        Map<Long, Integer> vertexAt = new HashMap<>();
    }

    @BeforeAll
    public static void loadFixtures () throws Exception {
        for (String name : List.of("latah", "newcastle")) {
            Fixture fixture = new Fixture();
            fixture.dir = new File(WalkshedsRegressionTest.class.getResource("walksheds/" + name).toURI());
            String dataset = new File(fixture.dir, "dataset").getAbsolutePath();
            TransportNetworkConfig config = new TransportNetworkConfig();
            config.pruneIslands = false;
            TransportNetwork network = TransportNetwork.fromOsw(dataset, config);
            fixture.streets = network.streetLayer;
            VertexStore.Vertex vertex = fixture.streets.vertexStore.getCursor();
            for (int v = 0; v < fixture.streets.vertexStore.getVertexCount(); v++) {
                vertex.seek(v);
                fixture.vertexAt.put(key(vertex.getFixedLon(), vertex.getFixedLat()), v);
            }
            fixture.cases = MAPPER.readTree(new File(fixture.dir, "cases.json"));
            fixture.http = new OswDemoServer(dataset, profilesDir()).start(0, null);
            FIXTURES.put(name, fixture);
        }
    }

    @AfterAll
    public static void stopServers () {
        for (Fixture fixture : FIXTURES.values()) fixture.http.stop();
    }

    @Test
    public void latahCostsMatchExactly () throws Exception {
        assertExact(FIXTURES.get("latah"));
    }

    /** The only fixture in which streets are used, so the only check on what street avoidance costs. */
    @Test
    public void newcastleStreetCostsMatchExactly () throws Exception {
        assertExact(FIXTURES.get("newcastle"));
    }

    @Test
    public void latahEndToEnd () throws Exception {
        assertEndToEnd(FIXTURES.get("latah"));
    }

    @Test
    public void newcastleEndToEnd () throws Exception {
        assertEndToEnd(FIXTURES.get("newcastle"));
    }

    // ------------------------------------------------------------------------------------------------ Exact

    private static void assertExact (Fixture fixture) throws Exception {
        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (JsonNode c : fixture.cases.get("walksheds")) {
            JsonNode walksheds = response(fixture, c);
            if (!"Ok".equals(walksheds.path("code").asText())) continue; // No costs to compare: see the end-to-end test.
            String name = c.get("response").asText();
            double maxCost = c.get("max_cost").asDouble();

            // Walksheds' cost at each node it reached. Its other "nodes" are the origin and the points where the
            // cost ran out partway along an edge, which stand at no node of the network.
            Map<Integer, Double> expected = new HashMap<>();
            double[] origin = null;
            for (JsonNode n : walksheds.get("node_costs")) {
                Integer vertex = vertexAt(fixture, n.get(0).asDouble(), n.get(1).asDouble());
                if (vertex != null) expected.put(vertex, n.get(2).asDouble());
                if (n.get(2).asDouble() == 0) origin = new double[] {n.get(0).asDouble(), n.get(1).asDouble()};
            }
            assertTrue(origin != null, name + " has no origin");

            // Start from Walksheds' own costs at the ends of the edge its origin is on, so that where and how the
            // origin attaches is not part of what is compared.
            Map<Integer, Double> start = new HashMap<>();
            Integer originVertex = vertexAt(fixture, origin[0], origin[1]);
            if (originVertex != null) start.put(originVertex, 0.0);
            for (JsonNode edge : walksheds.get("edges")) {
                JsonNode coordinates = edge.get("coordinates");
                JsonNode first = coordinates.get(0), last = coordinates.get(coordinates.size() - 1);
                for (JsonNode[] ends : new JsonNode[][] {{first, last}, {last, first}}) {
                    if (!samePosition(ends[0], origin)) continue;
                    Integer far = vertexAt(fixture, ends[1].get(0).asDouble(), ends[1].get(1).asDouble());
                    if (far != null && expected.containsKey(far)) start.put(far, expected.get(far));
                }
            }
            assertTrue(!start.isEmpty(), name + ": no node found at either end of the origin's edge");

            PedestrianCostSpec spec = spec(c);
            double[] cost = OswWalkshedMain.exactDijkstra(fixture.streets, spec, start, BASE_SPEED,
                    maxCost + EXACT_TOLERANCE_SECONDS);

            for (Map.Entry<Integer, Double> e : expected.entrySet()) {
                if (start.containsKey(e.getKey())) continue;
                compared++;
                double r5 = cost[e.getKey()];
                if (Double.isInfinite(r5)) {
                    problems.add(String.format("%s: Walksheds reaches %s at %.1f, R5 does not reach it",
                            name, describe(fixture, e.getKey()), e.getValue()));
                } else if (Math.abs(r5 - e.getValue()) > EXACT_TOLERANCE_SECONDS) {
                    problems.add(String.format("%s: %s costs %.1f in Walksheds, %.1f in R5",
                            name, describe(fixture, e.getKey()), e.getValue(), r5));
                }
            }
            for (int v = 0; v < cost.length; v++) {
                if (cost[v] < maxCost - EXACT_TOLERANCE_SECONDS && !expected.containsKey(v)) {
                    problems.add(String.format("%s: R5 reaches %s at %.1f, Walksheds does not reach it",
                            name, describe(fixture, v), cost[v]));
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.size() + " differences from Walksheds that osw-tools/DIFFERENCES.md "
                + "does not account for:\n" + String.join("\n", problems));
        assertTrue(compared > 20, "Only " + compared + " node costs were compared: is the fixture intact?");
    }

    // ------------------------------------------------------------------------------------------------ End to end

    private static void assertEndToEnd (Fixture fixture) throws Exception {
        List<String> problems = new ArrayList<>();
        for (JsonNode c : fixture.cases.get("walksheds")) {
            JsonNode walksheds = response(fixture, c);
            String name = c.get("response").asText();
            double maxCost = c.get("max_cost").asDouble();
            JsonNode r5 = get(fixture, "/api/v1/routing/reachable_tree/custom.json?lon=" + c.get("lon").asDouble()
                    + "&lat=" + c.get("lat").asDouble() + "&max_cost=" + maxCost + profileQuery(c));
            String expectedCode = walksheds.path("code").asText(), code = r5.path("code").asText();
            if (!expectedCode.equals(code)) {
                problems.add(String.format("%s: Walksheds answers %s, R5 answers %s", name, expectedCode, code));
                continue;
            }
            if (!"Ok".equals(code)) continue;
            String path = fixture.dir.getName() + "/" + name;
            if (START_ON_TIED_EDGES.stream().anyMatch(path::contains)) continue;

            Map<Integer, Double> expected = new HashMap<>(), actual = new HashMap<>();
            for (JsonNode n : walksheds.get("node_costs")) {
                Integer vertex = vertexAt(fixture, n.get(0).asDouble(), n.get(1).asDouble());
                if (vertex != null) expected.put(vertex, n.get(2).asDouble());
            }
            for (JsonNode n : r5.get("node_costs").get("features")) {
                JsonNode at = n.get("geometry").get("coordinates");
                Integer vertex = vertexAt(fixture, at.get(0).asDouble(), at.get(1).asDouble());
                if (vertex != null) actual.put(vertex, n.get("properties").get("cost").asDouble());
            }
            for (Map.Entry<Integer, Double> e : expected.entrySet()) {
                Double cost = actual.get(e.getKey());
                if (cost == null) {
                    if (e.getValue() < maxCost * END_TO_END_REACH_SHARE) {
                        problems.add(String.format("%s: Walksheds reaches %s at %.1f, R5 does not reach it",
                                name, describe(fixture, e.getKey()), e.getValue()));
                    }
                } else if (Math.abs(cost - e.getValue()) > END_TO_END_SECONDS + END_TO_END_SHARE * e.getValue()) {
                    problems.add(String.format("%s: %s costs %.1f in Walksheds, %.1f in R5",
                            name, describe(fixture, e.getKey()), e.getValue(), cost));
                }
            }
            for (Map.Entry<Integer, Double> e : actual.entrySet()) {
                if (e.getValue() < maxCost * END_TO_END_REACH_SHARE && !expected.containsKey(e.getKey())) {
                    problems.add(String.format("%s: R5 reaches %s at %.1f, Walksheds does not reach it",
                            name, describe(fixture, e.getKey()), e.getValue()));
                }
            }
        }
        for (JsonNode c : fixture.cases.get("routes")) {
            JsonNode r5 = get(fixture, "/api/v1/routing/shortest_path/custom.json?lon1=" + c.get("lon1").asDouble()
                    + "&lat1=" + c.get("lat1").asDouble() + "&lon2=" + c.get("lon2").asDouble()
                    + "&lat2=" + c.get("lat2").asDouble() + profileQuery(c));
            boolean found = "Ok".equals(r5.path("code").asText());
            if (found != c.get("found").asBoolean()) {
                problems.add(String.format("Route from %s, %s to %s, %s (%s): Walksheds %s, R5 answers %s",
                        c.get("lat1"), c.get("lon1"), c.get("lat2"), c.get("lon2"), profileQuery(c),
                        c.get("found").asBoolean() ? "finds one" : "finds none", r5.path("code").asText()));
            }
        }
        assertTrue(problems.isEmpty(), problems.size() + " differences from Walksheds beyond the allowance for the "
                + "accepted ones:\n" + String.join("\n", problems));
    }

    // ------------------------------------------------------------------------------------------------ Helpers

    private static File profilesDir () {
        File dir = new File("osw-tools/profiles");
        assertTrue(new File(dir, "ws-prod.json").isFile(), "Run the tests from the root of the repository, which "
                + "has the cost profile at osw-tools/profiles/ws-prod.json");
        return dir;
    }

    private static JsonNode response (Fixture fixture, JsonNode c) throws Exception {
        return MAPPER.readTree(new File(fixture.dir, c.get("response").asText()));
    }

    /** The production cost function with a case's parameters, as the API would resolve them. */
    private static PedestrianCostSpec spec (JsonNode c) throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("uphill", c.get("uphill").asDouble());
        parameters.put("downhill", c.get("downhill").asDouble());
        parameters.put("avoid_curbs", c.get("avoidCurbs").asInt() != 0);
        parameters.put("street_avoidance", c.get("streetAvoidance").asDouble());
        parameters.put("fan_out", c.get("fanOut").asBoolean());
        return PedestrianCostProfile.fromFile(new File(profilesDir(), "ws-prod.json")).resolve(parameters);
    }

    /** A case's parameters as the quality reports send them, with Python's True and False. */
    private static String profileQuery (JsonNode c) {
        return "&uphill=" + c.get("uphill").asDouble() + "&downhill=" + c.get("downhill").asDouble()
                + "&avoidCurbs=" + c.get("avoidCurbs").asInt() + "&streetAvoidance=" + c.get("streetAvoidance").asDouble()
                + "&reverse=0&fanOut=" + (c.get("fanOut").asBoolean() ? "True" : "False");
    }

    private static JsonNode get (Fixture fixture, String pathAndQuery) throws Exception {
        URI uri = URI.create("http://localhost:" + fixture.http.port() + pathAndQuery);
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), URLEncoder.encode(pathAndQuery, StandardCharsets.UTF_8)
                + " answered " + response.body());
        return MAPPER.readTree(response.body());
    }

    private static long key (long fixedLon, long fixedLat) {
        return (fixedLon << 32) ^ (fixedLat & 0xffffffffL);
    }

    /**
     * @return the vertex at a position, or null. R5 keeps positions in whole units of 1e-7 degrees, cut off rather
     *         than rounded, so a vertex may be one unit away from where the dataset and Walksheds put it.
     */
    private static Integer vertexAt (Fixture fixture, double lon, double lat) {
        long x = Math.round(lon * 1e7), y = Math.round(lat * 1e7);
        for (long dx = -1; dx <= 1; dx++) {
            for (long dy = -1; dy <= 1; dy++) {
                Integer vertex = fixture.vertexAt.get(key(x + dx, y + dy));
                if (vertex != null) return vertex;
            }
        }
        return null;
    }

    private static boolean samePosition (JsonNode coordinate, double[] position) {
        return Math.abs(coordinate.get(0).asDouble() - position[0]) < 1.5e-7
                && Math.abs(coordinate.get(1).asDouble() - position[1]) < 1.5e-7;
    }

    private static String describe (Fixture fixture, int vertex) {
        String id = fixture.streets.edgeStore.oswAttributes.oswNodeId(vertex);
        VertexStore.Vertex v = fixture.streets.vertexStore.getCursor(vertex);
        return "node " + id + " (" + v.getLat() + ", " + v.getLon() + ")";
    }
}
