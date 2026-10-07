"""Generate the small synthetic OSW 0.2 dataset used to test R5's OSW support against Unweaver.

    python3 make_test_fixture.py ../../src/test/resources/com/conveyal/r5/osw/tiny

Layout (meters; x east, y north), two sidewalks along a street with three crossings:

    P1 --footway-- N1 ----- N2 ----- N3 -- N4 (2 m, 20% grade: short steep segment, tolerated)
                   |        |  \\     |  \\
                   |       KN2  ST1  KN3  E1 (elevator, 45 s)
             crossing,      |  steps  |
             no kerbs      KS2        KS3 (raised kerb: no curb ramps)
                   |        |         |
                   S1 ----- S2 ----- S3 --road-- R1
                    3% up    10% up east

 - N2-N3 has interior geometry points; N1-N2 is 2% downhill going east.
 - Crossing KS2-KN2 has lowered kerbs at both ends (passable with avoid_curbs).
 - Crossing S1-N1 has no kerb nodes (impassable with avoid_curbs, matching Unweaver's curbramps semantics).
 - Footway P1 (no footway=* tag), steps ST1 and road R1 match no layer: impassable under otherEdges=impassable.
 - Node "n-west" (P1) has a non-numeric ID.
"""
import json
import math
import os
import sys

LAT0, LON0 = 47.6500, -122.3000
M_PER_DEG_LAT = 111320.0
M_PER_DEG_LON = 111320.0 * math.cos(math.radians(LAT0))


def ll(x, y):
    return [round(LON0 + x / M_PER_DEG_LON, 7), round(LAT0 + y / M_PER_DEG_LAT, 7)]


def haversine(coords):
    r = 6371008.8
    total = 0.0
    for (lon1, lat1), (lon2, lat2) in zip(coords, coords[1:]):
        p1, p2 = math.radians(lat1), math.radians(lat2)
        dp, dl = p2 - p1, math.radians(lon2 - lon1)
        a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
        total += 2 * r * math.asin(math.sqrt(a))
    return round(total, 2)


NODES = {
    "1": (0, 0, {}), "2": (100, 0, {}), "3": (200, 0, {}),
    "11": (0, 20, {}), "12": (100, 20, {}), "13": (200, 20, {}), "14": (202, 20, {}),
    "21": (100, 2, {"barrier": "kerb", "kerb": "lowered"}),
    "22": (100, 18, {"barrier": "kerb", "kerb": "lowered"}),
    "31": (200, 2, {"barrier": "kerb", "kerb": "raised"}),
    "32": (200, 18, {"barrier": "kerb", "kerb": "lowered"}),
    "41": (205, 25, {}),
    "51": (110, 30, {}),
    "61": (260, 0, {}),
    "n-west": (-50, 20, {}),
}

# (id, u, v, interior points, properties)
EDGES = [
    ("101", "1", "2", [], {"highway": "footway", "footway": "sidewalk", "incline": 0.03}),
    ("102", "2", "3", [], {"highway": "footway", "footway": "sidewalk", "incline": 0.10}),
    ("103", "11", "12", [], {"highway": "footway", "footway": "sidewalk", "incline": -0.02}),
    ("104", "12", "13", [(130, 22), (170, 22)], {"highway": "footway", "footway": "sidewalk", "incline": 0.0}),
    ("105", "13", "14", [], {"highway": "footway", "footway": "sidewalk", "incline": 0.2}),
    ("106", "2", "21", [], {"highway": "footway", "footway": "sidewalk", "incline": 0.01}),
    ("107", "22", "12", [], {"highway": "footway", "footway": "sidewalk", "incline": -0.01}),
    ("108", "21", "22", [], {"highway": "footway", "footway": "crossing", "crossing": "marked"}),
    ("109", "3", "31", [], {"highway": "footway", "footway": "sidewalk", "incline": 0.0}),
    ("110", "32", "13", [], {"highway": "footway", "footway": "sidewalk", "incline": 0.0}),
    ("111", "31", "32", [], {"highway": "footway", "footway": "crossing", "crossing": "marked"}),
    ("112", "1", "11", [], {"highway": "footway", "footway": "crossing", "crossing": "unmarked"}),
    ("113", "13", "41", [], {"highway": "elevator"}),
    ("114", "12", "51", [], {"highway": "steps", "incline": "up"}),
    ("115", "n-west", "11", [], {"highway": "footway", "incline": 0.0}),
    ("116", "3", "61", [], {"highway": "residential", "name": "Example St"}),
]


def build():
    nodes = []
    for nid, (x, y, tags) in NODES.items():
        nodes.append({"type": "Feature", "geometry": {"type": "Point", "coordinates": ll(x, y)},
                      "properties": {"_id": nid, **tags}})
    edges = []
    for eid, u, v, interior, props in EDGES:
        coords = [ll(*NODES[u][:2])] + [ll(x, y) for x, y in interior] + [ll(*NODES[v][:2])]
        edges.append({"type": "Feature", "geometry": {"type": "LineString", "coordinates": coords},
                      "properties": {"_id": eid, "_u_id": u, "_v_id": v, "length": haversine(coords), **props}})
    return ({"type": "FeatureCollection", "$schema": "https://sidewalks.washington.edu/opensidewalks/0.2/schema.json",
             "features": nodes},
            {"type": "FeatureCollection", "$schema": "https://sidewalks.washington.edu/opensidewalks/0.2/schema.json",
             "features": edges})


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "tiny"
    os.makedirs(out, exist_ok=True)
    nodes, edges = build()
    with open(os.path.join(out, "tiny.nodes.geojson"), "w") as f:
        json.dump(nodes, f, indent=1)
    with open(os.path.join(out, "tiny.edges.geojson"), "w") as f:
        json.dump(edges, f, indent=1)
    print("Wrote", out)
