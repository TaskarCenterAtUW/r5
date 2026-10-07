"""Prepare an OSW 0.2 dataset as an Unweaver layer so Unweaver and R5 see the same network.

    python3 prepare_osw_for_unweaver.py DATASET OUT_DIR

DATASET is an OSW .zip, a directory containing *.nodes.geojson and *.edges.geojson, or an *.edges.geojson file.
Writes OUT_DIR/layers/osw.geojson, ready for:  unweaver build OUT_DIR --changes-sign incline

Each edge gets:
  _u, _v      the OSW _u_id/_v_id, so Unweaver's node IDs are the OSW node IDs (R5 reports the same IDs)
  curbramps   an existing curbramps value is kept if R5 also recognizes it (true/false/yes/no/1/0), as 1 or 0.
              Otherwise: 1 if OSW kerb nodes at both ends are kerb=lowered or kerb=flush; 0 if either end has any other
              kerb; absent if kerb information is missing at either end. This is the same rule R5 applies to the
              raw OSW nodes (StreetLayer.curbRampStatus). An existing curbramps property is kept.
  length      haversine length in meters if the property was missing (OSW convention)
  keys        characters other than letters, digits and _ in property names become _ ("crossing:markings" ->
              "crossing_markings"), which Unweaver requires; profiles still match on the original OSW names
  incline     numeric strings are converted to numbers; other values (e.g. steps "up") are moved to incline_text,
              since R5 treats those as flat anyway

Non-LineString edges are dropped, as Unweaver and R5 both ignore them.
"""
import json
import math
import re
import os
import sys
import zipfile

from pedestrian_profile import CURB_RAMPS_UNKNOWN, CURB_RAMPS_YES, parse_curb_ramps

RAMP_KERBS = {"lowered", "flush"}

# *.edges.geojson or *.edges.OSW.geojson (the schema repository's example data), ignoring case. Mirrors OswReader.
EDGES_FILE = re.compile(r".*\.edges(\.osw)?\.geojson$", re.IGNORECASE)
NODES_FILE = re.compile(r".*\.nodes(\.osw)?\.geojson$", re.IGNORECASE)


def _read_dataset(path):
    def load(f):
        return json.load(f)["features"]

    if os.path.isdir(path):
        names = os.listdir(path)
        nodes = [n for n in names if NODES_FILE.match(n)]
        edges = [n for n in names if EDGES_FILE.match(n)]
        with open(os.path.join(path, edges[0])) as f:
            e = load(f)
        n = []
        if nodes:
            with open(os.path.join(path, nodes[0])) as f:
                n = load(f)
        return n, e
    if EDGES_FILE.match(path):
        with open(path) as f:
            e = load(f)
        i = path.lower().rindex(".edges.")
        nodes_path = path[:i] + ".nodes." + path[i + 7:]
        n = []
        if os.path.exists(nodes_path):
            with open(nodes_path) as f:
                n = load(f)
        return n, e
    with zipfile.ZipFile(path) as z:
        names = [x for x in z.namelist() if "__MACOSX" not in x]
        edges = [x for x in names if EDGES_FILE.match(x)]
        nodes = [x for x in names if NODES_FILE.match(x)]
        with z.open(edges[0]) as f:
            e = load(f)
        n = []
        if nodes:
            with z.open(nodes[0]) as f:
                n = load(f)
        return n, e


def unweaver_key(key):
    """Unweaver stores properties as SQLite columns and fails on names like "crossing:markings", so characters other
    than letters, digits and underscores become underscores. pedestrian_profile.py matches either form."""
    return re.sub(r"[^0-9A-Za-z_]", "_", key)


def _haversine(coords):
    r = 6371008.8
    total = 0.0
    for (lon1, lat1, *_), (lon2, lat2, *_) in zip(coords, coords[1:]):
        p1, p2 = math.radians(lat1), math.radians(lat2)
        dp, dl = p2 - p1, math.radians(lon2 - lon1)
        a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
        total += 2 * r * math.asin(math.sqrt(a))
    return total


def _kerb(props):
    if props is None:
        return None
    if props.get("kerb") is not None:
        return str(props["kerb"]).strip().lower()
    if props.get("barrier") == "kerb":
        return "unknown"
    return None


def curbramps(u_props, v_props):
    k0, k1 = _kerb(u_props), _kerb(v_props)
    if (k0 is not None and k0 not in RAMP_KERBS) or (k1 is not None and k1 not in RAMP_KERBS):
        return False
    if k0 is not None and k1 is not None:
        return True
    return None


def prepare(dataset, out_dir):
    nodes, edges = _read_dataset(dataset)
    node_props = {}
    for f in nodes:
        p = f.get("properties") or {}
        if "_id" in p:
            node_props[str(p["_id"])] = p
    out = []
    for f in edges:
        g = f.get("geometry") or {}
        if g.get("type") != "LineString" or len(g.get("coordinates", [])) < 2:
            continue
        p = {unweaver_key(k): v for k, v in (f.get("properties") or {}).items() if v is not None}
        u, v = p.get("_u_id"), p.get("_v_id")
        if u is not None and v is not None:
            p["_u"], p["_v"] = str(u), str(v)
        # A recognized explicit curbramps value is kept (as 1/0); otherwise derive it from kerb nodes, as R5 does.
        explicit = parse_curb_ramps(p.pop("curbramps", None))
        if explicit != CURB_RAMPS_UNKNOWN:
            p["curbramps"] = 1 if explicit == CURB_RAMPS_YES else 0
        else:
            c = curbramps(node_props.get(str(u)), node_props.get(str(v)))
            if c is not None:
                # 1/0 rather than true/false: Unweaver's GeoPackage tables have no boolean column type.
                p["curbramps"] = 1 if c else 0
        # Unweaver negates incline on reverse edges; a non-numeric incline (steps "up"/"down") would make the GeoJSON
        # driver type the whole column as text and break that. R5 treats non-numeric inclines as flat, so drop them.
        if "incline" in p and not isinstance(p["incline"], bool) and isinstance(p["incline"], str):
            try:
                p["incline"] = float(p["incline"].strip())  # R5 parses numeric strings too
            except ValueError:
                pass
        if "incline" in p and (isinstance(p["incline"], bool) or not isinstance(p["incline"], (int, float))):
            p["incline_text"] = str(p.pop("incline"))
        if "length" not in p:
            # Rounded to centimeters exactly as R5's OswReader does (Java Math.round: floor(x + 0.5)).
            p["length"] = math.floor(_haversine(g["coordinates"]) * 100 + 0.5) / 100
        out.append({"type": "Feature", "geometry": g, "properties": p})
    os.makedirs(os.path.join(out_dir, "layers"), exist_ok=True)
    path = os.path.join(out_dir, "layers", "osw.geojson")
    with open(path, "w") as f:
        json.dump({"type": "FeatureCollection", "features": out}, f)
    return path, len(out)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    path, n = prepare(sys.argv[1], sys.argv[2])
    print("Wrote %d edges to %s" % (n, path))
