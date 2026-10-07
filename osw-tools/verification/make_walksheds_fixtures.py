"""Turn Walksheds output saved by the TDEI quality reports into fixtures for R5's WalkshedsRegressionTest.

    python3 make_walksheds_fixtures.py REPORTS_REPO OUT_DIR

REPORTS_REPO is a checkout of TDEI-quality-reports. OUT_DIR is normally
../../src/test/resources/com/conveyal/r5/osw/walksheds. Two fixtures are written:

 latah      Dataset 211cba13-5f11-4be3-b248-3bc73ba12d1e (191 edges, no roads), from test/tmp_data. The raw
            reachable_tree response for each of its 3 points of interest under each of the reports' 4 profiles, and
            whether a route was found between each pair of them. The request parameters are the reports' own
            (config.WALKSHED_PROFILE_PARAMS).
 newcastle  One raw reachable_tree response on a dataset with roads (de21a3cd-b363-40e1-b66c-9543b182571c), from
            test/roads_tests, with the part of the dataset within reach of it. This one came from the Walksheds web
            page, not the reports, so its parameters are not recorded anywhere. They are worked out from the response:
            the cost of its residential streets fixes the street avoidance (exp(3 * 0.5) times the walking time), and
            the cost of its sidewalks fixes the incline limits.

Each response is cut down to what the test reads: the origin, each reached node's position and cost, and each
edge's ID and geometry.
"""
import csv
import json
import math
import os
import sys

LATAH = "211cba13-5f11-4be3-b248-3bc73ba12d1e"

# The reports' profiles when the Latah responses were captured (config.WALKSHED_PROFILE_PARAMS, September 2026).
LATAH_PROFILES = {
    "unconstrained_pedestrian_(sidewalks_only)":
        {"uphill": 0.15, "downhill": 0.15, "avoidCurbs": 0, "streetAvoidance": 1, "fanOut": False},
    "manual_wheelchair":
        {"uphill": 0.083, "downhill": 0.083, "avoidCurbs": 1, "streetAvoidance": 1, "fanOut": False},
    "sidewalks_whenever_possible":
        {"uphill": 0.15, "downhill": 0.15, "avoidCurbs": 0, "streetAvoidance": 0.9, "fanOut": False},
    "fan_out":
        {"uphill": 0.15, "downhill": 0.15, "avoidCurbs": 0, "streetAvoidance": 0.9, "fanOut": True},
}

# Worked out from the response itself: see the module docstring.
NEWCASTLE_PARAMS = {"uphill": 0.08, "downhill": 0.1, "avoidCurbs": 1, "streetAvoidance": 0.5, "fanOut": False}
NEWCASTLE_MAX_COST = 400
# How far around the response's origin to keep, in meters. A cost of 400 reaches at most 520 m at 1.3 m/s.
NEWCASTLE_MARGIN_METERS = 600
# The properties the cost profiles and the curb ramp rule read. The rest are dropped to keep the fixture small.
EDGE_PROPERTIES = ("_id", "_u_id", "_v_id", "highway", "footway", "foot", "incline", "length", "crossing:markings")
NODE_PROPERTIES = ("_id", "barrier", "kerb")


def trim(response):
    """Keep what the test reads from a reachable_tree response."""
    if "edges" not in response:
        return {"code": response.get("code")}
    return {
        "code": "Ok",
        "origin": response["origin"]["geometry"]["coordinates"],
        "node_costs": [[n["geometry"]["coordinates"][0], n["geometry"]["coordinates"][1], n["properties"]["cost"]]
                       for n in response["node_costs"]["features"]],
        "edges": [{"_id": str(e["properties"]["_id"]), "coordinates": e["geometry"]["coordinates"]}
                  for e in response["edges"]["features"]],
    }


def write(path, value):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(value, f, separators=(",", ":"))
        f.write("\n")


def latah(reports, out):
    src = os.path.join(reports, "test", "tmp_data", LATAH)
    for part in ("edges", "nodes"):
        with open(os.path.join(src, "raw", "osw", part + ".geojson")) as f:
            write(os.path.join(out, "latah", "dataset", "latah.%s.geojson" % part), json.load(f))
    with open(os.path.join(src, "raw", "pois.csv")) as f:
        pois = [{"index": i, "lat": float(r["lat"]), "lon": float(r["lon"])}
                for i, r in enumerate(csv.DictReader(f), start=1)]
    with open(os.path.join(src, "analytics", "routing", "amenities_0.25_mi.csv")) as f:
        pairs = [{"index": i, "lat1": float(r["Lat1"]), "lon1": float(r["Lon1"]),
                  "lat2": float(r["Lat2"]), "lon2": float(r["Lon2"])} for i, r in enumerate(csv.DictReader(f))]
    walksheds, routes = [], []
    for profile, params in LATAH_PROFILES.items():
        for poi in pois:
            name = "poi_%d_%s.json" % (poi["index"], profile)
            with open(os.path.join(src, "analytics", "walkshed_geojson", "debug", name)) as f:
                write(os.path.join(out, "latah", "walksheds", name), trim(json.load(f)))
            walksheds.append({"response": "walksheds/" + name, "lat": poi["lat"], "lon": poi["lon"],
                              "max_cost": 600, **params})
        for pair in pairs:
            name = "route_%d_profile_%s.geojson" % (pair["index"], profile)
            with open(os.path.join(src, "analytics", "routing", "walkshed_geojson", name)) as f:
                found = bool(json.load(f)["features"][0]["properties"].get("success"))
            routes.append({**{k: v for k, v in pair.items() if k != "index"}, "found": found, **params})
    write(os.path.join(out, "latah", "cases.json"), {
        "source": "TDEI-quality-reports test/tmp_data/%s, Walksheds responses captured 2026-09-15" % LATAH,
        "walksheds": walksheds, "routes": routes})


def newcastle(reports, out):
    src = os.path.join(reports, "test", "roads_tests")
    with open(os.path.join(src, "WS_response1.geojson")) as f:
        response = json.load(f)
    lon, lat = response["origin"]["geometry"]["coordinates"]

    def near(coordinate):
        east = (coordinate[0] - lon) * 111320 * math.cos(math.radians(lat))
        north = (coordinate[1] - lat) * 111320
        return abs(east) <= NEWCASTLE_MARGIN_METERS and abs(north) <= NEWCASTLE_MARGIN_METERS

    def only(feature, names):
        feature["properties"] = {k: v for k, v in feature["properties"].items() if k in names and v is not None}
        return feature

    dataset = "de21a3cd-b363-40e1-b66c-9543b182571c"
    with open(os.path.join(src, dataset + ".edges.geojson")) as f:
        edges = json.load(f)
    edges["features"] = [only(e, EDGE_PROPERTIES) for e in edges["features"]
                         if any(near(c) for c in e["geometry"]["coordinates"])]
    kept = {str(e["properties"][k]) for e in edges["features"] for k in ("_u_id", "_v_id")}
    with open(os.path.join(src, dataset + ".nodes.geojson")) as f:
        nodes = json.load(f)
    nodes["features"] = [only(n, NODE_PROPERTIES) for n in nodes["features"]
                         if str(n["properties"]["_id"]) in kept or near(n["geometry"]["coordinates"])]
    write(os.path.join(out, "newcastle", "dataset", "newcastle.edges.geojson"), edges)
    write(os.path.join(out, "newcastle", "dataset", "newcastle.nodes.geojson"), nodes)
    write(os.path.join(out, "newcastle", "walksheds", "walkshed.json"), trim(response))
    write(os.path.join(out, "newcastle", "cases.json"), {
        "source": "TDEI-quality-reports test/roads_tests/WS_response1.geojson, on part of dataset " + dataset
                  + ". Parameters worked out from the response's costs.",
        "walksheds": [{"response": "walksheds/walkshed.json", "lat": lat, "lon": lon,
                       "max_cost": NEWCASTLE_MAX_COST, **NEWCASTLE_PARAMS}],
        "routes": []})
    print("newcastle: kept %d edges and %d nodes" % (len(edges["features"]), len(nodes["features"])))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    latah(sys.argv[1], sys.argv[2])
    newcastle(sys.argv[1], sys.argv[2])
