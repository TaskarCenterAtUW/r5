"""Run an Unweaver shortest_path_tree and reachable_tree query in-process (no server needed) and save the result,
stamped with the pedestrian profile's provenance identifiers, for comparison with R5 (compare_walksheds.py).

    python3 query_unweaver.py PROJECT_DIR PROFILE_ID LON LAT MAX_COST OUT.json ['{"uphill": 0.083, "base_speed": 1.3}']

Requires the current Unweaver (github.com/nbolten/unweaver) on the Python path, and a project built with
prepare_osw_for_unweaver.py and `unweaver build PROJECT_DIR --changes-sign incline`.
"""
import json
import os
import sys

from unweaver.parsers import parse_profiles
from unweaver.server.run import setup_app

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # osw-tools/, for pedestrian_profile
import pedestrian_profile  # noqa: E402


def query(project, profile_id, lon, lat, max_cost, args):
    app = setup_app(project)
    client = app.test_client()
    params = {"lon": lon, "lat": lat, "max_cost": max_cost}
    params.update({k: (str(v).lower() if isinstance(v, bool) else v) for k, v in args.items()})
    results = {}
    for view in ("shortest_path_tree", "reachable_tree"):
        r = client.get("/%s/%s.json" % (view, profile_id), query_string=params)
        if r.status_code != 200:
            raise RuntimeError("%s failed (%s): %s" % (view, r.status_code, r.get_data(as_text=True)))
        results[view] = r.get_json()
    return results


def provenance(project, profile_id, args):
    for profile in parse_profiles(project):
        if profile["id"] == profile_id:
            gen = profile["cost_function"]
            params = {k: v for k, v in args.items() if k not in ("base_speed", "timestamp")}
            fun = gen(None, **params)
            p = dict(getattr(fun, "provenance", {}))
            p["baseSpeed"] = args.get("base_speed", pedestrian_profile.WALK_BASE)
            return p
    return {}


if __name__ == "__main__":
    if len(sys.argv) < 7:
        print(__doc__)
        sys.exit(1)
    project, profile_id = sys.argv[1], sys.argv[2]
    lon, lat, max_cost = float(sys.argv[3]), float(sys.argv[4]), float(sys.argv[5])
    out = sys.argv[6]
    args = json.loads(sys.argv[7]) if len(sys.argv) > 7 else {}
    project = os.path.abspath(project)
    result = query(project, profile_id, lon, lat, max_cost, args)
    result["provenance"] = provenance(project, profile_id, args)
    result["provenance"]["engine"] = "unweaver"
    result["query"] = {"lon": lon, "lat": lat, "max_cost": max_cost, "args": args}
    with open(out, "w") as f:
        json.dump(result, f, indent=1)
    sp = result["shortest_path_tree"]
    print("Reached %d nodes; wrote %s" % (len(sp.get("node_costs", {}).get("features", [])), out))
