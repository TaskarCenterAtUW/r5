"""Compare an R5 walkshed (OswWalkshedMain output) with an Unweaver walkshed (query_unweaver.py output).

    python3 compare_walksheds.py R5.json UNWEAVER.json [--tolerance 1e-6] [--report report.json]

Checks, in order:
 1. Provenance: both runs used the same profileId, runSpecId (profile + parameter values) and base speed.
 2. Node costs: R5's exact (unrounded) costs vs Unweaver's shortest_path_tree node_costs, joined on node coordinates
    (7 decimal places, falling back to the nearest node within 0.5 m, since R5 stores coordinates as integers). Should agree to floating point tolerance.
 3. Node costs, R5 router: R5's integer-second router costs vs Unweaver. These differ by rounding (each edge rounded
    up to a whole second) and millimeter edge lengths; reported as a distribution rather than pass/fail.
 4. Reachable edges: OSW edge IDs in Unweaver's reachable_tree vs edges R5 can traverse within max_cost, wholly or
    partially (the walkshed boundary).

Exit status is 0 if checks 1, 2 and 4 pass, 1 otherwise.
"""
import argparse
import json
import math
import sys


def key(lon, lat):
    return (round(lon, 7), round(lat, 7))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("r5")
    ap.add_argument("unweaver")
    ap.add_argument("--tolerance", type=float, default=1e-6, help="max absolute difference in seconds (exact)")
    ap.add_argument("--report", help="write a JSON report here")
    a = ap.parse_args()

    with open(a.r5) as f:
        r5 = json.load(f)
    with open(a.unweaver) as f:
        uw = json.load(f)
    ok = True
    report = {"r5Provenance": r5.get("provenance"), "unweaverProvenance": uw.get("provenance")}

    # 1. Provenance
    pr, pu = r5.get("provenance", {}), uw.get("provenance", {})
    mismatched = [k for k in ("profileId", "runSpecId") if pr.get(k) != pu.get(k)]
    if float(pr.get("baseSpeed", -1)) != float(pu.get("baseSpeed", -2)):
        mismatched.append("baseSpeed")
    report["provenanceMismatches"] = mismatched
    if mismatched:
        ok = False
        print("PROVENANCE MISMATCH: %s differ" % ", ".join(mismatched))
        for k in mismatched:
            print("   r5 %s=%s   unweaver %s=%s" % (k, pr.get(k), k, pu.get(k)))
    else:
        print("Provenance OK: profileId %s, runSpecId %s, baseSpeed %s" % (pr["profileId"], pr["runSpecId"],
                                                                          pr["baseSpeed"]))

    # Unweaver returns only a code when the origin is invalid, e.g. when no edge leaving it is passable. If R5 also
    # reached nothing beyond the origin, the engines agree: compare empty results.
    codes = {view: uw[view].get("code") for view in ("shortest_path_tree", "reachable_tree") if uw[view].get("code")}
    if codes:
        print("Unweaver returned %s" % codes)
        for view in codes:
            uw[view] = {"node_costs": {"features": []}, "edges": {"features": []}}
        if len(r5["exact"]) <= 1:
            print("R5 also reached nothing beyond the origin: treating as agreement.")
            r5 = dict(r5, exact=[], router=[])
        else:
            ok = False

    # 2/3. Node costs
    uw_costs = {}
    for feat in uw["shortest_path_tree"]["node_costs"]["features"]:
        lon, lat = feat["geometry"]["coordinates"][:2]
        uw_costs[key(lon, lat)] = feat["properties"]["cost"]

    def compare(nodes, label, tolerance):
        r5_costs = {key(n["lon"], n["lat"]): (n["node"], n["cost"]) for n in nodes}
        pairs = {k: k for k in r5_costs.keys() & uw_costs.keys()}
        # R5 stores coordinates as fixed-point integers, so a node can differ from Unweaver's copy in the 7th decimal
        # place (about 1 cm). Match the remaining nodes to the nearest unmatched Unweaver node within 0.5 m.
        left_uw = list(uw_costs.keys() - pairs.values())
        for k in r5_costs.keys() - pairs.keys():
            best, best_d = None, 0.5
            for u in left_uw:
                d = math.hypot((u[0] - k[0]) * 111320 * math.cos(math.radians(k[1])), (u[1] - k[1]) * 111320)
                if d < best_d:
                    best, best_d = u, d
            if best is not None:
                pairs[k] = best
                left_uw.remove(best)
        only_r5 = sorted(r5_costs[k][0] for k in r5_costs.keys() - pairs.keys())
        only_uw = sorted("%s,%s" % k for k in left_uw)
        diffs = []
        for k, u in pairs.items():
            node, c = r5_costs[k]
            diffs.append((c - uw_costs[u], node, c, uw_costs[u]))
        diffs.sort(key=lambda d: -abs(d[0]))
        max_abs = abs(diffs[0][0]) if diffs else 0.0
        result = {"nodesCompared": len(diffs), "onlyInR5": only_r5, "onlyInUnweaver": only_uw,
                  "maxAbsDiffSeconds": max_abs,
                  "worst": [{"node": n, "r5": c, "unweaver": u, "diff": d} for d, n, c, u in diffs[:10]]}
        passed = not only_r5 and not only_uw and (tolerance is None or max_abs <= tolerance)
        status = "" if tolerance is None else ("PASS" if passed else "FAIL")
        print("%s %s: %d nodes compared, max |diff| %.3g s, only in R5: %d, only in Unweaver: %d" % (
            status, label, len(diffs), max_abs, len(only_r5), len(only_uw)))
        if only_r5:
            print("   only in R5:", only_r5[:20])
        if only_uw:
            print("   only in Unweaver:", only_uw[:20])
        if tolerance is not None and max_abs > tolerance:
            for w in result["worst"][:5]:
                print("   node %s: r5 %.6f unweaver %.6f diff %.3g" % (w["node"], w["r5"], w["unweaver"], w["diff"]))
        return passed, result

    passed, report["exact"] = compare(r5["exact"], "Node costs (R5 exact)", a.tolerance)
    ok = ok and passed
    _, report["router"] = compare(r5["router"], "Node costs (R5 router, integer seconds)", None)
    if report["router"]["nodesCompared"]:
        diffs = sorted(w["diff"] for w in report["router"]["worst"])
        print("   router - unweaver, worst 10 (s):", ", ".join("%.2f" % d for d in diffs))

    # 4. Reachable edges (undirected, by OSW edge ID). Unweaver's reachable_tree includes every passable edge leaving a
    # reached node, whole or partial, so compare with R5's fully reachable plus boundary (partial) edges.
    r5_full = set(e["edge"] for e in r5.get("exactReachableEdges", []))
    r5_partial = set(e["edge"] for e in r5.get("exactPartialEdges", [])) - r5_full
    r5_edges = r5_full | r5_partial
    uw_edges = set()
    uw_node_pairs = set()
    for feat in uw["reachable_tree"]["edges"]["features"]:
        p = feat["properties"]
        if p.get("_id") is not None:
            uw_edges.add(str(p["_id"]))
        if p.get("_u") is not None and p.get("_v") is not None:
            uw_node_pairs.add(frozenset((str(p["_u"]), str(p["_v"]))))
    only_r5 = sorted(r5_edges - uw_edges)
    only_uw = sorted(uw_edges - r5_edges)
    # Unweaver's graph holds one edge per pair of nodes, so of two OSW edges joining the same nodes it keeps only one.
    # R5 keeps both. An R5-only edge whose end nodes Unweaver joins with another edge is that case, not a mismatch.
    r5_pairs = {}
    for e in r5.get("exactReachableEdges", []) + r5.get("exactPartialEdges", []):
        r5_pairs[e["edge"]] = frozenset((str(e["u"]), str(e["v"])))
    parallel = sorted(e for e in only_r5 if r5_pairs.get(e) in uw_node_pairs)
    only_r5 = [e for e in only_r5 if e not in parallel]
    report.setdefault("notes", []).extend(
        "edge %s duplicates another edge between the same nodes; Unweaver keeps only one of them" % e for e in parallel)
    report["edges"] = {"r5": len(r5_edges), "r5Partial": sorted(r5_partial), "unweaver": len(uw_edges),
                       "onlyInR5": only_r5, "onlyInUnweaver": only_uw, "parallelEdges": parallel}
    edges_ok = not only_r5 and not only_uw
    print("%s Reachable edges: R5 %d (%d partial), Unweaver %d, only in R5 %s, only in Unweaver %s" % (
        "PASS" if edges_ok else "FAIL", len(r5_edges), len(r5_partial), len(uw_edges), only_r5, only_uw))
    if parallel:
        print("   Not counted: edge%s %s join%s the same nodes as another edge, and Unweaver keeps only one edge per "
              "node pair." % ("s" if len(parallel) > 1 else "", ", ".join(parallel), "" if len(parallel) > 1 else "s"))
    ok = ok and edges_ok

    report["pass"] = ok
    if a.report:
        with open(a.report, "w") as f:
            json.dump(report, f, indent=1)
    print("OVERALL: %s" % ("PASS" if ok else "FAIL"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
