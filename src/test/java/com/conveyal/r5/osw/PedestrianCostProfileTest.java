package com.conveyal.r5.osw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pedestrian cost profile format, its identifiers, and Unweaver-equivalent edge costs.
 * The expected identifiers are also asserted by osw-tools/verification/test_pedestrian_profile.py, which keeps the
 * Java and Python implementations (used by R5 and Unweaver respectively) in agreement.
 */
public class PedestrianCostProfileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Shared with osw-tools/verification/test_pedestrian_profile.py. */
    static final String PROFILE_ID = "6d87a93db06e4151686f7bd7b07ee2bd";
    static final String RUN_SPEC_ID_DEFAULTS = "ef7e971e5b97d19665222263fc63b4e6";
    static final String RUN_SPEC_ID_UPHILL_083 = "015efe75b5854214a521decc187d8330";
    static final String RUN_SPEC_ID_NO_CURB_AVOIDANCE = "9b67b0e9d42998b48b5ad67ae19da156";

    static PedestrianCostProfile loadProfile () throws Exception {
        File file = new File(PedestrianCostProfileTest.class.getResource("unweaver-dynamic.json").toURI());
        return PedestrianCostProfile.fromFile(file);
    }

    @Test
    public void canonicalJsonIgnoresFormatting () throws Exception {
        JsonNode a = MAPPER.readTree("{\"b\": [1.0, 0.10, -0.0, 1e-5, 30], \"a\": \"x\\\"\\n\", \"\u00e9\": true, \"c\": null}");
        JsonNode b = MAPPER.readTree("{\"\u00e9\":true,\"c\":null,\"a\":\"x\\\"\\n\",\"b\":[1,0.1,0,0.00001,3E1]}");
        String expected = "{\"a\":\"x\\\"\\n\",\"b\":[1,0.1,0,0.00001,30],\"c\":null,\"\u00e9\":true}";
        assertEquals(expected, CanonicalJson.canonicalize(a));
        assertEquals(expected, CanonicalJson.canonicalize(b));
    }

    @Test
    public void identifiersMatchPython () throws Exception {
        PedestrianCostProfile profile = loadProfile();
        assertEquals(PROFILE_ID, profile.profileId);
        assertEquals(RUN_SPEC_ID_DEFAULTS, profile.resolve(null).runSpecId);
        assertEquals(RUN_SPEC_ID_UPHILL_083, profile.resolve(Map.of("uphill", 0.083)).runSpecId);
        // Supplying a default explicitly does not change the run spec.
        assertEquals(RUN_SPEC_ID_UPHILL_083,
                profile.resolve(Map.of("uphill", 0.083, "downhill", 0.1, "avoid_curbs", true)).runSpecId);
        assertEquals(RUN_SPEC_ID_NO_CURB_AVOIDANCE, profile.resolve(Map.of("avoid_curbs", false)).runSpecId);
    }

    @Test
    public void anyChangeToTheDefinitionChangesTheProfileId () throws Exception {
        PedestrianCostProfile profile = loadProfile();
        JsonNode changed = profile.source.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.get("layers").get(1)).put("delaySeconds", 31);
        assertNotEquals(profile.profileId, new PedestrianCostProfile(changed).profileId);
        // Reformatting does not.
        String reformatted = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(profile.source);
        assertEquals(profile.profileId, PedestrianCostProfile.fromJson(reformatted).profileId);
    }

    @Test
    public void costsMatchUnweaverCostDynamic () throws Exception {
        PedestrianCostSpec spec = loadProfile().resolve(null);
        double[] out = new double[2];
        Map<String, String> sidewalk = Map.of("highway", "footway", "footway", "sidewalk");

        // At Tobler's ideal grade, full speed.
        spec.evaluate(sidewalk, -0.0087, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        assertEquals(76.92307692307692, PedestrianCostSpec.seconds(100, 1.3, out[0], out[1]), 1e-9);

        // At the uphill limit, speed is base / 5 (Unweaver's DIVISOR).
        spec.evaluate(sidewalk, 0.085, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        assertEquals(0.2, out[0], 1e-12);
        assertEquals(384.6153846153846, PedestrianCostSpec.seconds(100, 1.3, out[0], out[1]), 1e-9);

        // Beyond the limits: impassable, unless the edge is 3 m or shorter.
        spec.evaluate(sidewalk, 0.09, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        assertTrue(Double.isNaN(out[0]));
        spec.evaluate(sidewalk, -0.11, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        assertTrue(Double.isNaN(out[0]));
        spec.evaluate(sidewalk, 0.2, 3, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        assertTrue(out[0] > 0);

        // With no incline the edge is walked at plain speed, however long and whatever the limits. That is not the
        // same as flat: level ground is a little slower than the ideal grade.
        spec.evaluate(sidewalk, Double.NaN, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        assertEquals(1.0, out[0]);
        spec.evaluate(sidewalk, 0, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        assertTrue(out[0] < 1.0);

        // Crossings: 30 s delay added to travel time (Unweaver's overwrite bug fixed), curb ramps required.
        Map<String, String> crossing = Map.of("highway", "footway", "footway", "crossing");
        spec.evaluate(crossing, Double.NaN, 20, OswEdgeAttributes.CURB_RAMPS_YES, out);
        assertEquals(45.38461538461539, PedestrianCostSpec.seconds(20, 1.3, out[0], out[1]), 1e-9);
        spec.evaluate(crossing, Double.NaN, 20, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        assertTrue(Double.isNaN(out[0]));
        spec.evaluate(crossing, Double.NaN, 20, OswEdgeAttributes.CURB_RAMPS_NO, out);
        assertTrue(Double.isNaN(out[0]));
        PedestrianCostSpec noCurbs = loadProfile().resolve(Map.of("avoid_curbs", false));
        noCurbs.evaluate(crossing, Double.NaN, 20, OswEdgeAttributes.CURB_RAMPS_NO, out);
        assertEquals(30, out[1]);

        // Edges matching no layer are impassable; edges with no OSW tags (e.g. transit links) are walked normally.
        spec.evaluate(Map.of("highway", "residential"), 0, 50, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        assertTrue(Double.isNaN(out[0]));
        spec.evaluate(null, 0, 50, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        assertEquals(1, out[0]);
        assertEquals(0, out[1]);
    }

    @Test
    public void invalidParametersAreRejected () throws Exception {
        PedestrianCostProfile profile = loadProfile();
        assertThrows(IllegalArgumentException.class, () -> profile.resolve(Map.of("uphil", 0.1)));
        assertThrows(IllegalArgumentException.class, () -> profile.resolve(Map.of("uphill", 2)));
        assertThrows(IllegalArgumentException.class, () -> profile.resolve(Map.of("avoid_curbs", "yes")));
        Map<String, Object> wrongType = new HashMap<>();
        wrongType.put("uphill", "steep");
        assertThrows(IllegalArgumentException.class, () -> profile.resolve(wrongType));
    }

    /** Mirrored by test_blocked_and_avoidance in osw-tools/verification/test_pedestrian_profile.py. */
    @Test
    public void layersCanBeBlockedOrAvoided () throws Exception {
        PedestrianCostProfile profile = PedestrianCostProfile.fromJson("{\"parameters\": {"
                + "\"avoid\": {\"type\": \"number\", \"default\": 0.5}, "
                + "\"no_steps\": {\"type\": \"boolean\", \"default\": false}, "
                + "\"fan\": {\"type\": \"boolean\", \"default\": false}}, \"layers\": ["
                + "{\"name\": \"steps\", \"match\": {\"highway\": \"steps\"}, \"speedFactor\": 0.5, "
                + "\"blocked\": \"$no_steps\"}, "
                + "{\"name\": \"streets\", \"match\": {\"highway\": \"residential\"}, \"delaySeconds\": 10, "
                + "\"avoidance\": {\"amount\": \"$avoid\", \"k\": 3, \"unless\": \"$fan\"}}]}");
        double[] out = new double[2];
        Map<String, String> steps = Map.of("highway", "steps"), street = Map.of("highway", "residential");

        profile.resolve(null).evaluate(steps, Double.NaN, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        assertEquals(100 / (1.3 * 0.5), PedestrianCostSpec.seconds(100, 1.3, out[0], out[1]), 1e-9);
        profile.resolve(Map.of("no_steps", true)).evaluate(steps, Double.NaN, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        assertTrue(Double.isNaN(out[0]));

        // The whole cost, delay included, is multiplied by exp(k * amount).
        profile.resolve(null).evaluate(street, Double.NaN, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        assertEquals((100 / 1.3 + 10) * Math.exp(1.5), PedestrianCostSpec.seconds(100, 1.3, out[0], out[1]), 1e-9);
        profile.resolve(Map.of("avoid", 0)).evaluate(street, Double.NaN, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        assertEquals(100 / 1.3 + 10, PedestrianCostSpec.seconds(100, 1.3, out[0], out[1]), 1e-9);
        // At an amount of 1 the layer is impassable.
        profile.resolve(Map.of("avoid", 1)).evaluate(street, Double.NaN, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        assertTrue(Double.isNaN(out[0]));
        // "unless" switches the avoidance off altogether, even at 1.
        profile.resolve(Map.of("avoid", 1, "fan", true))
                .evaluate(street, Double.NaN, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, out);
        assertEquals(100 / 1.3 + 10, PedestrianCostSpec.seconds(100, 1.3, out[0], out[1]), 1e-9);

        assertThrows(IllegalArgumentException.class, () -> PedestrianCostProfile.fromJson(
                "{\"layers\": [{\"name\": \"x\", \"blocked\": \"yes\"}]}").resolve(null));
        assertThrows(IllegalArgumentException.class, () -> PedestrianCostProfile.fromJson(
                "{\"layers\": [{\"name\": \"x\", \"avoidance\": {\"k\": 2}}]}"));
    }

    /** Mirrored by test_incline_direction in osw-tools/verification/test_pedestrian_profile.py. */
    @Test
    public void inclineCanBeTakenAsMappedWhicheverWayAnEdgeIsWalked () throws Exception {
        String layer = "{\"layers\": [{\"name\": \"paths\", \"match\": {\"highway\": \"footway\"}, \"inclineSpeed\": "
                + "{\"maxUphill\": 0.1, \"maxDownhill\": 0.05%s}}]}";
        PedestrianCostSpec travel = PedestrianCostProfile.fromJson(String.format(layer, "")).resolve(null);
        PedestrianCostSpec mapped = PedestrianCostProfile.fromJson(
                String.format(layer, ", \"direction\": \"mapped\"")).resolve(null);
        Map<String, String> path = Map.of("highway", "footway");
        double[] forward = new double[2], backward = new double[2];

        // An edge mapped as 8% uphill, walked forwards and then backwards (where it is 8% downhill).
        // By default the backward walk is downhill, and past the 5% downhill limit.
        travel.evaluate(path, 0.08, 0.08, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, forward);
        travel.evaluate(path, -0.08, 0.08, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, backward);
        assertTrue(forward[0] > 0);
        assertTrue(Double.isNaN(backward[0]));
        // Taken as mapped, it is 8% uphill both ways: usable, and at the same speed.
        mapped.evaluate(path, 0.08, 0.08, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, forward);
        mapped.evaluate(path, -0.08, 0.08, 100, OswEdgeAttributes.CURB_RAMPS_UNKNOWN, backward);
        assertTrue(forward[0] > 0);
        assertEquals(forward[0], backward[0]);

        assertThrows(IllegalArgumentException.class, () -> PedestrianCostProfile.fromJson(
                String.format(layer, ", \"direction\": \"sideways\"")));
    }

    @Test
    public void reservedKeysAndWronglyTypedSettingsAreRejected () {
        assertThrows(IllegalArgumentException.class, () -> PedestrianCostProfile.fromJson(
                "{\"layers\": [{\"name\": \"x\", \"match\": {\"curbramps\": \"1\"}}]}"));
        assertThrows(IllegalArgumentException.class, () -> PedestrianCostProfile.fromJson(
                "{\"layers\": [{\"name\": \"x\", \"requireCurbRamps\": \"false\"}]}").resolve(null));
        assertThrows(IllegalArgumentException.class, () -> PedestrianCostProfile.fromJson(
                "{\"layers\": [{\"name\": \"x\", \"delaySeconds\": \"30\"}]}").resolve(null));
    }

    @Test
    public void explicitCurbRampsParsingMatchesPython () {
        assertEquals(OswEdgeAttributes.CURB_RAMPS_YES, OswEdgeAttributes.parseCurbRamps("1.0"));
        assertEquals(OswEdgeAttributes.CURB_RAMPS_YES, OswEdgeAttributes.parseCurbRamps("true"));
        assertEquals(OswEdgeAttributes.CURB_RAMPS_NO, OswEdgeAttributes.parseCurbRamps("0"));
        assertEquals(OswEdgeAttributes.CURB_RAMPS_UNKNOWN, OswEdgeAttributes.parseCurbRamps("maybe"));
    }

    @Test
    public void misspelledSettingsAreRejected () {
        assertThrows(IllegalArgumentException.class, () -> PedestrianCostProfile.fromJson(
                "{\"layers\": [{\"name\": \"x\", \"delaySecs\": 30}]}"));
        assertThrows(IllegalArgumentException.class, () -> PedestrianCostProfile.fromJson(
                "{\"otherEdges\": \"maybe\"}"));
    }
}
