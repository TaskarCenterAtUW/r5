"""Checks that the Python profile implementation (used with Unweaver) agrees with R5's Java implementation.

    python3 -m unittest test_pedestrian_profile      (from osw-tools/verification/; no Unweaver needed)

The identifiers and costs asserted here are also asserted by PedestrianCostProfileTest.java on the same profile file.
"""
import json
import math
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))  # the modules under test are one level up, in osw-tools/

import pedestrian_profile as pp  # noqa: E402
import prepare_osw_for_unweaver as prep  # noqa: E402

TEST_RESOURCES = os.path.join(HERE, "..", "..", "src", "test", "resources", "com", "conveyal", "r5", "osw")
PROFILE = os.path.join(TEST_RESOURCES, "unweaver-dynamic.json")

# Shared with PedestrianCostProfileTest.java
PROFILE_ID = "6d87a93db06e4151686f7bd7b07ee2bd"
RUN_SPEC_ID_DEFAULTS = "ef7e971e5b97d19665222263fc63b4e6"
RUN_SPEC_ID_UPHILL_083 = "015efe75b5854214a521decc187d8330"
RUN_SPEC_ID_NO_CURB_AVOIDANCE = "9b67b0e9d42998b48b5ad67ae19da156"


class TestPedestrianProfile(unittest.TestCase):

    def setUp(self):
        self.profile = pp.PedestrianCostProfile.from_file(PROFILE)

    def test_canonical_json(self):
        a = json.loads('{"b": [1.0, 0.10, -0.0, 1e-5, 30], "a": "x\\"\\n", "\u00e9": true, "c": null}')
        b = json.loads('{"\u00e9":true,"c":null,"a":"x\\"\\n","b":[1,0.1,0,0.00001,3E1]}')
        expected = '{"a":"x\\"\\n","b":[1,0.1,0,0.00001,30],"c":null,"\u00e9":true}'
        self.assertEqual(expected, pp.canonical_json(a))
        self.assertEqual(expected, pp.canonical_json(b))

    def test_identifiers_match_java(self):
        self.assertEqual(PROFILE_ID, self.profile.profile_id)
        self.assertEqual(RUN_SPEC_ID_DEFAULTS, self.profile.resolve({}).run_spec_id)
        self.assertEqual(RUN_SPEC_ID_UPHILL_083, self.profile.resolve({"uphill": 0.083}).run_spec_id)
        self.assertEqual(RUN_SPEC_ID_UPHILL_083, self.profile.resolve(
            {"uphill": 0.083, "downhill": 0.1, "avoid_curbs": True}).run_spec_id)
        self.assertEqual(RUN_SPEC_ID_NO_CURB_AVOIDANCE, self.profile.resolve({"avoid_curbs": False}).run_spec_id)

    def test_costs_match_java(self):
        s = self.profile.resolve({})
        sidewalk = {"highway": "footway", "footway": "sidewalk", "length": 100.0}
        self.assertAlmostEqual(76.92307692307692, s.edge_seconds(dict(sidewalk, incline=-0.0087), 1.3), places=9)
        self.assertAlmostEqual(384.6153846153846, s.edge_seconds(dict(sidewalk, incline=0.085), 1.3), places=9)
        self.assertIsNone(s.edge_seconds(dict(sidewalk, incline=0.09), 1.3))
        self.assertIsNone(s.edge_seconds(dict(sidewalk, incline=-0.11), 1.3))
        self.assertIsNotNone(s.edge_seconds(dict(sidewalk, incline=0.2, length=3.0), 1.3))
        # With no incline the edge is walked at plain speed, which is not the same as flat.
        self.assertAlmostEqual(100 / 1.3, s.edge_seconds(sidewalk, 1.3), places=9)
        self.assertGreater(s.edge_seconds(dict(sidewalk, incline=0.0), 1.3), 100 / 1.3)
        crossing = {"highway": "footway", "footway": "crossing", "length": 20.0}
        self.assertAlmostEqual(45.38461538461539, s.edge_seconds(dict(crossing, curbramps=1), 1.3), places=9)
        self.assertIsNone(s.edge_seconds(crossing, 1.3))
        self.assertIsNone(s.edge_seconds(dict(crossing, curbramps=0), 1.3))
        self.assertIsNone(s.edge_seconds({"highway": "residential", "length": 50.0}, 1.3))

    def test_invalid_parameters(self):
        for bad in ({"uphil": 0.1}, {"uphill": 2}, {"avoid_curbs": "yes"}, {"uphill": "steep"}):
            with self.assertRaises(ValueError):
                self.profile.resolve(bad)

    def test_unweaver_generator_signatures(self):
        # Current Unweaver passes the graph positionally; the 2019 version does not.
        f1 = pp.cost_fun_generator(None, profile_path=PROFILE, base_speed=1.3, uphill=0.083)
        f2 = pp.cost_fun_generator(profile_path=PROFILE, base_speed=1.3, uphill=0.083)
        self.assertEqual(RUN_SPEC_ID_UPHILL_083, f1.provenance["runSpecId"])
        self.assertEqual(RUN_SPEC_ID_UPHILL_083, f2.provenance["runSpecId"])

    def test_curb_ramps_rule_matches_r5(self):
        lowered = {"barrier": "kerb", "kerb": "lowered"}
        raised = {"barrier": "kerb", "kerb": "raised"}
        self.assertEqual(True, prep.curbramps(lowered, {"kerb": "flush"}))
        self.assertEqual(False, prep.curbramps(lowered, raised))
        self.assertEqual(False, prep.curbramps(None, {"kerb": "rolled"}))
        self.assertEqual(False, prep.curbramps(lowered, {"barrier": "kerb"}))  # a kerb with no type given
        # An end with no kerb node is no obstacle.
        self.assertEqual(True, prep.curbramps(lowered, None))
        self.assertEqual(True, prep.curbramps(None, None))

    def test_explicit_curb_ramps_parsing_matches_r5(self):
        self.assertEqual(pp.CURB_RAMPS_YES, pp.parse_curb_ramps("1.0"))
        self.assertEqual(pp.CURB_RAMPS_YES, pp.parse_curb_ramps(True))
        self.assertEqual(pp.CURB_RAMPS_NO, pp.parse_curb_ramps(0))
        self.assertEqual(pp.CURB_RAMPS_UNKNOWN, pp.parse_curb_ramps("maybe"))

    def test_blocked_and_avoidance(self):
        # Mirrors layersCanBeBlockedOrAvoided in PedestrianCostProfileTest.java
        profile = pp.PedestrianCostProfile({
            "parameters": {"avoid": {"type": "number", "default": 0.5},
                           "no_steps": {"type": "boolean", "default": False},
                           "fan": {"type": "boolean", "default": False}},
            "layers": [
                {"name": "steps", "match": {"highway": "steps"}, "speedFactor": 0.5, "blocked": "$no_steps"},
                {"name": "streets", "match": {"highway": "residential"}, "delaySeconds": 10,
                 "avoidance": {"amount": "$avoid", "k": 3, "unless": "$fan"}}]})
        steps = {"highway": "steps", "length": 100.0}
        street = {"highway": "residential", "length": 100.0}
        self.assertAlmostEqual(100 / (1.3 * 0.5), profile.resolve({}).edge_seconds(steps, 1.3), places=9)
        self.assertIsNone(profile.resolve({"no_steps": True}).edge_seconds(steps, 1.3))
        self.assertAlmostEqual((100 / 1.3 + 10) * math.exp(1.5), profile.resolve({}).edge_seconds(street, 1.3), places=9)
        self.assertAlmostEqual(100 / 1.3 + 10, profile.resolve({"avoid": 0}).edge_seconds(street, 1.3), places=9)
        self.assertIsNone(profile.resolve({"avoid": 1}).edge_seconds(street, 1.3))
        self.assertAlmostEqual(100 / 1.3 + 10, profile.resolve({"avoid": 1, "fan": True}).edge_seconds(street, 1.3),
                               places=9)
        with self.assertRaises(ValueError):
            pp.PedestrianCostProfile({"layers": [{"name": "x", "blocked": "yes"}]}).resolve({})
        with self.assertRaises(ValueError):
            pp.PedestrianCostProfile({"layers": [{"name": "x", "avoidance": {"k": 2}}]})

    def test_reserved_keys_and_setting_types_rejected(self):
        with self.assertRaises(ValueError):
            pp.PedestrianCostProfile({"layers": [{"name": "x", "match": {"curbramps": "1"}}]}).resolve({})
        with self.assertRaises(ValueError):
            pp.PedestrianCostProfile({"layers": [{"name": "x", "requireCurbRamps": "false"}]}).resolve({})
        with self.assertRaises(ValueError):
            pp.PedestrianCostProfile({"layers": [{"name": "x", "delaySeconds": "30"}]}).resolve({})


if __name__ == "__main__":
    unittest.main()
