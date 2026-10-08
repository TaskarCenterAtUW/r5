# Differences between Walksheds and R5

R5's Walksheds-compatible API (see the [README](README.md), step 10) is meant to give the same answers as the TDEI
Walksheds service, which runs on Unweaver. It mostly does. This file lists where it does not, why, and what was
decided about each.

## How close they are

Measured on one dataset: Seattle, TDEI dataset `54f968ac-d036-401d-9bf3-6d9a804c55f7`, 162,650 edges. Routes that the
production Walksheds server returned for the TDEI quality reports in May 2026 were requested again from R5, with the
same two profiles the reports used (Unconstrained Pedestrian and Manual Wheelchair). The figures are for a random
sample of 4,000 of those routes.

Every percentage in this file is a share of those 4,000 routes.

| Outcome | Routes | Share |
|---|---|---|
| Both find a route, lengths within 2% | 3,444 | 86.1% |
| Neither finds a route | 498 | 12.5% |
| **The two agree** | **3,942** | **98.6%** |
| Start and end on the same edge | 32 | 0.8% |
| Two edges equally near the start or end | 10 | 0.3% |
| Two edges between the same two nodes | 5 | 0.1% |
| Short steep edges | 4 | 0.1% |
| Two paths that cost nearly the same | 2 | 0.1% |
| **Differ, cause known** | **53** | **1.3%** |
| Only R5 finds a route | 4 | 0.1% |
| Walksheds misses a cheaper path, for no known reason | 1 | 0.0% |
| **Differ, cause not known** | **5** | **0.1%** |

So on 99.9% of routes the two either agree or differ for a known reason. Each cause is described under
[Remaining](#remaining) below, with its status.

Of the routes whose lengths differ, 11 (0.3%) are within 5% and 24 (0.6%) are more than 25% apart. Costs match
closely: 3,431 routes (85.8%) have costs within 1% of each other, and R5's are a median 0.06% higher.

These figures are with R5's compatibility mode on, which is the default: see
[Attaching a point to the network](#attaching-a-point-to-the-network). With it off the two agree on 91.0% of routes.

These numbers come from one dataset with no road edges. What streets cost has been checked separately, against
one Walksheds walkshed on a dataset with roads (see [Tests](#tests)): at a street avoidance of 0.5, R5's costs
match. Fan mode on a dataset with roads, and the rules listed under [Not implemented](#not-implemented), have not
been compared with Walksheds output.

## Where the differences come from

Every cause found so far, with what was done about it. **Fixed** means R5 was changed and the difference is gone.
The sections below describe each cause.

### Fixed

| Cause | Status | Effect of the fix, on the same sample |
|---|---|---|
| R5 attached a point to the nearest edge even if the traveller could not use it, and only within 30 m | **Fixed** | Routes Walksheds finds and R5 does not: 485 → 92 of 3,000 |
| R5's profile ignored incline on crossings and never used steps | **Fixed** | Routes on which the two disagree about a route existing: 128 → 83 of 4,000 |
| R5 required a lowered or flush kerb at both ends of a crossing for it to have curb ramps | **Fixed** | Wheelchair routes only Walksheds finds: 54 → 5 |
| R5 joined edges by node ID, leaving steps and other edges that share only a position unconnected | **Fixed** | Pedestrian routes only Walksheds finds: 24 → 0. Routes over 25% different in length: 58 → 36 |
| R5 treated an edge with no incline as flat, which is slower than the plain speed Walksheds walks it at | **Fixed** | No change on this sample, where nearly every edge has an incline. On a dataset with roads, which have none, every street cost 17% too much: found by the regression tests |
| R5 reversed an edge's incline when it was walked backwards, and Walksheds costs an edge the same both ways | **Fixed** | Routes with lengths within 2%: 2,956 → 3,105 of 4,000. Routes with costs within 5%: about 2,130 → 3,320 |
| R5 rounded each edge's cost up to a whole second, and Walksheds does not round | **Fixed** | Routes with lengths within 2%: 3,399 → 3,444 of 4,000. R5's costs were a median 2% higher than Walksheds', now 0.06% |
| R5 attached a point somewhere other than Walksheds does on the edge, and sometimes to an edge that was not the nearest | **Fixed**, in compatibility mode | Routes with lengths within 2%: 3,105 → 3,399 of 4,000. Routes only Walksheds finds: 5 → 0 |

### Remaining

The 58 routes on which the two differ (1.5%), by cause. To find the cause where the lengths differ, each engine's
path was costed with the Walksheds cost function.

| Cause | Routes | Share | Status |
|---|---|---|---|
| The start and end are on the same edge, and Walksheds walks to one end of it and back | 32 | 0.8% | **Left as is.** A limitation of Unweaver, reported as TDEI issue 4420. R5 walks straight there. |
| Two edges are equally near the start or end, and each engine attaches to a different one | 10 | 0.3% | **Left as is.** Walksheds' choice between them cannot be predicted. |
| Two edges join the same two nodes, and Unweaver keeps only one | 5 | 0.1% | **Left as is.** R5 keeps both, and uses the shorter. |
| Walksheds walks a short steep edge R5 has closed | 4 | 0.1% | **Open.** Probably how each measures 3 m. |
| Two paths cost within 1% of each other, and each engine takes a different one | 2 | 0.1% | **Left as is.** |
| **Cause known** | **53** | **1.3%** | |
| Only R5 finds a route | 4 | 0.1% | **Unexplained.** |
| R5's path is cheaper by Walksheds' own costs, yet Walksheds did not take it | 1 | 0.0% | **Unexplained.** |
| **Cause not known** | **5** | **0.1%** | |

In all: on 98.6% of routes the two agree, on 1.3% they differ for a known reason, and on 0.1% they differ for a
reason not yet understood.

## Differences in how a route is costed

### Whole seconds

R5's router counts time in whole seconds. It used to round every edge up, with a minimum of one second, which made
costs a median 2% higher than Walksheds' and walksheds 2 to 3% smaller. With a pedestrian cost profile it now keeps
the fraction of a second along each path and rounds only the total, so paths are compared by their unrounded
costs, and the Walksheds-compatible API reports costs to a thousandth of a second.

What is left is small. Costs are a median 0.06% above Walksheds', and 98% of routes are within 1%. Walksheds works
from lengths that are not rounded to the centimeter as the dataset's are, which accounts for a few hundredths of a
second per edge.

The rest of R5 still reads times in whole seconds (travel time surfaces, transit access and egress). Those are now
the unrounded time rounded up once, so they are at most a second over, however many edges the path has.

### Short steep edges

Both ignore the incline limits on edges of 3 m or less. On 4 of the sampled routes (0.1%) Walksheds walks an edge
that is over the wheelchair limit and that R5 has closed. Three of those edges are 3.01 to 3.03 m long in the
dataset, and Walksheds reports each as 3.0 m: it appears to keep lengths to a tenth of a meter, so to it they are
not over 3 m. The fourth is 3.12 m long and is the last edge of its route, of which only 1.9 m is walked: Unweaver
applies the rule to the part walked, R5 to the whole edge. Neither has been confirmed in Walksheds' code, and R5 has
not been changed.

## Differences in where a route starts and ends

### Attaching a point to the network

Both engines attach a requested point to a nearby edge and start or end the route there. The Walksheds-compatible
API has a **compatibility mode**, on by default, in which it attaches points where Walksheds does. It is turned off
with the environment variable `WALKSHEDS_COMPAT=0`, or the server option `--walksheds-compat false`. The figures at
the top of this file are with it on.

**Which edge.** Both use the nearest edge, measured on the ground, within 50 m that the traveller can use.
Unweaver's source looks only at the four nearest edge records, but the deployed Walksheds does not behave that way:
it attaches to the nearest usable edge with up to four unusable ones nearer. So R5 applies no such limit.

**Where on the edge.** Walksheds finds the nearest place using longitude and latitude as they are, as if a degree of
each were the same length. At Seattle's latitude a degree of longitude is about two-thirds of a degree of latitude,
so that is not the nearest place on the ground: it lies further along the edge, by up to about 10 m. In
compatibility mode R5 does the same. With the mode off it attaches at the nearest place on the ground.

*Evidence.* Of the 6,986 ends of the sampled routes that both engines find, projecting in longitude and latitude
reproduces Walksheds' attachment point to within 1 m for 6,976. The nearest point on the ground does so for 5,599.

| | Compatibility mode on | Off |
|---|---|---|
| Route ends attached more than 5 m from Walksheds', of 6,996 | 11 | not measured |
| Routes that differ in length by more than 2% because they start or end at a different point | 10 (0.3%) | 239 (6.0%) |
| The two agree | 97.4% | 91.0% |

**Equally near edges.** The 11 ends still apart are where two edges are equally near, to within a few centimeters,
usually because the nearest place is the node where they meet. R5 takes the first in the dataset. Walksheds usually
does too (7 of 7 such ends in the Seattle sample where the choice matters), but not always: at one of the three
points in the Latah test fixture it takes the second. Nothing in Unweaver's source fixes the order.

**R5's own way of attaching a point is not used here.** R5's street router finds the nearest place on a segment with
`GeometryUtils.segmentFraction`, which divides longitudes by the cosine of the latitude where it should multiply by
it. Away from the equator its place is therefore off in the opposite direction to Walksheds', by about as much
again, and it can pick an edge that is not the nearest. An earlier version of this file called R5's point the
accurate one; it was not. The Walksheds-compatible API now finds the edge and the place itself, in both modes. The
rest of R5, including the map page's own walkshed call, still uses R5's router as it is.

The 50 m limit is inferred: routes from the deployed Walksheds start up to 50.0 m from the requested point and no
further. Unweaver's own default is 30 m.

### A point at a node

R5 treats a point within 10 cm of a node as being at the node. A point slightly further along an edge is charged
that edge's fixed delay in full (30 s on a crossing), or gets no route if the edge is closed. R5's snapping can
place a point that was given exactly at a node 10 to 30 cm along one of its edges. This affected 15 of 2,210 routes
to node positions in testing, and matters little for real points of interest.

## Differences in what Walksheds finds

### A start and end on the same edge

When both points attach to the same edge, Walksheds does not walk along the edge from one to the other. It walks
from the start to one end of the edge and back to the destination, or round by other edges. Unweaver's source notes
this as unfinished. R5 walks straight there. In 32 routes (0.8%) this makes Walksheds' route the longer: in one, 104
m where the two points are 31 m apart. Most of the routes whose lengths differ by more than 25% are of this kind.

*Decision.* R5 is left as it is, in both modes.

### Two edges between the same two nodes

Where two OSW edges join the same pair of nodes, Unweaver keeps one of them and R5 keeps both. On 5 routes (0.1%)
R5 uses the one Walksheds does not have.

## Not implemented

- **Reverse walksheds.** A request with `reverse` on gets a 501. In the production cost function, `reverseWalkshed`
  swaps the uphill and downhill limits.
- **Rules of the Walksheds cost function left out of the profiles.** Sidewalk scoring, avoiding primary streets,
  indoor footways' opening hours, transit edges, closed edges, and pathway stairs. The README says why for each.
  None occurs in the Seattle dataset.

## Differences in the response

- Edge features carry the OSW properties, with `:` in keys replaced by `_`, but not the `curbs`, `lowered_curbs` and
  `flush_curbs` counts that Walksheds adds.
- `_u` and `_v` are `"lon, lat"`. Walksheds adds a third, elevation, part for an end the route reached.
- Where nodes at the same position were joined (see below), an edge's `_u_id` or `_v_id` is the ID of the node kept,
  which may not be the ID in the dataset's edge record.
- When there is no route or no usable start point, the response is a 200 with `code` set to `NoPath` or
  `InvalidWaypoint`, as in Walksheds.

## Differences that were found and closed

These once made R5 differ and no longer do; their effect is in the [Fixed](#fixed) table above. They are listed
here as rules, because each is something R5 does only because Walksheds does.

| Rule | What Walksheds does, and R5 now does too |
|---|---|
| Curb ramps on a crossing | A crossing has ramps unless a kerb that is not lowered or flush, including one with no type given, stands at either end. An end with no kerb node is no obstacle. Agrees with Walksheds' `curbramps` flag on all 8,571 crossings checked. R5 used to require a lowered or flush kerb at both ends. |
| Joining edges | Edges are joined by where their ends are. Nodes at the same position, to 7 decimal places, are one node. The Seattle dataset has 1,202 such nodes, mostly the ends of steps, which R5 used to leave unconnected. |
| Incline, whichever way an edge is walked | An edge's `incline` is used as it is mapped in both directions: an edge mapped as 3% downhill is costed as 3% downhill walked either way, so it costs the same both ways. This is deliberate in Walksheds: what counts is how steep the edge is, since for a wheelchair user going down a slope is as hard as going up it. R5 used to reverse the sign for an edge walked backwards. Of 23,316 edges walked backwards in 2,633 stored Walksheds routes, every one was costed with the sign as mapped. The profiles set this with `"direction": "mapped"`. See the note below the table. |
| An edge with no incline | Walked at the plain speed for its kind, with no incline limit. Not treated as flat: level ground is slightly slower than the ideal grade, a gentle downhill. |
| Plain footways | Every `highway=footway` is usable, not only sidewalks and crossings. |
| Steps | Walked at 0.5 m/s, and closed when avoiding curbs. |
| Crossings and incline | Crossings are subject to the incline limits as well as their 30 s delay. |
| Fan mode | With `fanOut` on, streets cost the same as footways whatever the street avoidance. |

**A note on incline.** Walksheds uses the incline's sign as mapped, not its size. So the uphill limit applies to
edges mapped as rising and the downhill limit to edges mapped as falling, whichever way they are walked, and two
equal slopes drawn in opposite directions cost slightly differently, because the speed function peaks at a gentle
downhill. With the equal or near-equal limits the quality reports use, the effect is small. R5 copies it as it is.

## Tests

`WalkshedsRegressionTest` (in `src/test/java/com/conveyal/r5/osw/`) runs with the rest of R5's tests and fails if R5
moves away from Walksheds in a way this file does not account for. It compares R5 with answers saved from the
Walksheds service, in two ways:

- **Exactly, with the accepted differences taken out.** The network is costed with R5's profile, attributes and
  connections, by a search of its own that starts from Walksheds' own costs at the ends of the edge its origin is on.
  That removes the attachment point from the comparison, since some points still attach to a different edge. What
  is left must match Walksheds to within a second at every node, and R5 must reach exactly the nodes Walksheds does.
- **End to end.** R5's API is asked what Walksheds was asked. The answers must agree on whether there is a
  walkshed or route at all, on each node's cost to within 1 s plus 0.5%, and on which nodes are reached, apart from
  those within 1% of the cost limit.

The saved answers come from the TDEI quality reports' test data, and are under
`src/test/resources/com/conveyal/r5/osw/walksheds/`:

| Fixture | Dataset | Walksheds answers |
|---|---|---|
| `latah` | TDEI dataset `211cba13-5f11-4be3-b248-3bc73ba12d1e`: 191 edges, no roads, many crossings with untagged kerbs | The walkshed from each of 3 points under each of the reports' 4 profiles, one of which has no usable start; and whether a route exists between each pair of points (none does: they are on separate islands) |
| `newcastle` | Part of TDEI dataset `de21a3cd-b363-40e1-b66c-9543b182571c`, which has roads | One walkshed with a street avoidance of 0.5, in which residential streets are walked at a penalty |

`osw-tools/verification/make_walksheds_fixtures.py` builds the fixtures from a checkout of the reports. To add a
case, save a raw `reachable_tree` response and the request that produced it, add it there, and rerun it.

When one of these tests fails after a change, either the change is a mistake, or it is a new accepted difference. A
new accepted difference belongs in this file, and the test should be changed to take it out of the comparison.

## Shared behaviour worth knowing

R5 copies these from Walksheds, so they are not differences, but they affect results:

- **The crossing delay is charged per crossing edge.** Where a dataset splits a crossing at the road's centerline,
  crossing the street costs two delays. The TDEI quality reports' open questions describe this.
- **Starting partway along an edge with a fixed delay costs the whole delay**, as when Unweaver splits an edge.
