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
| Both find a route, lengths within 2% | 2,956 | 73.9% |
| Neither finds a route | 497 | 12.4% |
| **The two agree** | **3,453** | **86.3%** |
| Different start or end point | 269 | 6.7% |
| Costing rules | 200 | 5.0% |
| **Differ, cause known** | **469** | **11.7%** |
| Walksheds misses a cheaper path | 42 | 1.1% |
| R5 does not take a path cheaper by its own costs | 22 | 0.6% |
| Short steep edges | 4 | 0.1% |
| Only one of the two finds a route | 10 | 0.3% |
| **Differ, cause not known** | **78** | **2.0%** |

So on 98% of routes the two either agree or differ for a known reason, and 2% are unexplained. Each cause is
described under [Remaining](#remaining) below, with its status.

Of the routes whose lengths differ, 284 (7.1%) are within 5% and 36 (0.9%) are more than 25% apart. Costs are
further apart than lengths: about 2,130 routes (53%) have costs within 5% of each other, with R5's a median 2% higher.

These numbers come from one dataset with no road edges. Street costs, fan mode and the rules listed under
[Not implemented](#not-implemented) have not been compared with Walksheds output at all.

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

### Remaining

The 547 routes on which the two differ (13.7%), by cause. To find the cause where the lengths differ, each engine's
path was costed under both engines' rules: Walksheds' (incline as mapped, unrounded) and R5's (incline reversed when
walked backwards, each edge rounded up). Those two cost models reproduce the engines' own reported costs to within
0.1%.

| Cause | Routes | Share | Status |
|---|---|---|---|
| The route starts or ends at a different point, more than 5 m away | 269 | 6.7% | **Left as is.** Mostly Walksheds finding the nearest point on an edge in longitude and latitude instead of on the ground. R5's point is the accurate one. |
| Costing: each engine's path is the cheaper one under its own rules | 200 | 5.0% | **Left as is.** Incline direction, rounding and missing inclines. R5's incline handling is correct. |
| **Cause known** | **469** | **11.7%** | |
| R5's path is cheaper by Walksheds' own costs, yet Walksheds did not take it | 42 | 1.1% | **Unexplained.** Something in Walksheds or Unweaver. |
| Walksheds' path is cheaper by R5's own costs, yet R5 did not take it | 22 | 0.6% | **Unexplained.** Should not happen; to be investigated. |
| Walksheds walks an edge R5 has closed | 4 | 0.1% | **Open.** Short steep edges; not investigated. |
| Only one of the two finds a route | 10 | 0.3% | **Unexplained.** Five each way; not investigated. |
| **Cause not known** | **78** | **2.0%** | |

In all: on 86.3% of routes the two agree, on 11.7% they differ for a known reason, and on 2.0% they differ for a
reason not yet understood. The unexplained routes on Walksheds' side are the more severe: 12 of the 42 are more than
25% off, against none of the 22 on R5's side, 18 of which are within 5%.

## Differences in how a route is costed

### Walksheds does not reverse an edge's incline when it is walked backwards

An OSW edge's `incline` describes the edge in the direction it is mapped. Walked the other way, uphill becomes
downhill. R5 reverses the sign. Walksheds does not: an edge mapped as 3% downhill is costed as 3% downhill in both
directions.

*Evidence.* In 2,633 stored Walksheds routes, 46,386 edges had a known direction of travel, taken from the order of
nodes along the route. Of the 23,316 walked backwards, every one was reported, and costed, with the incline's sign
as mapped. None was reversed.

*Effect.* With equal uphill and downhill limits, as in the quality reports' profiles, the same edges are usable
either way, so this does not change which routes exist. It does change cost, by a few percent per edge, because
uphill is slower than downhill. That is enough to choose a different path where two are nearly equal, and it is the
largest known reason route costs differ. With unequal limits it would also change which edges are usable.

*Decision.* R5 is left as it is, since reversing the sign is correct. This is a defect to raise with the Walksheds
team.

### R5 rounds each edge's cost up to a whole second

R5 keeps time in whole seconds and rounds every edge up, with a minimum of one second. Walksheds adds unrounded
costs. Over a route of thirty short edges this adds some seconds, so R5's costs run slightly higher (a median of 2%
higher on routes of matching length), and near-ties can fall the other way.

R5 can compute unrounded costs (`OswWalkshedMain` reports them as `exact`), but its router does not use them.

### An edge with no incline

Walksheds walks an edge that has no `incline` at exactly the base speed. R5 treats it as flat, an incline of 0,
which is slightly slower than base speed because the fastest incline is a gentle downhill (-0.87%). At the default
limits the difference is about 16% on such an edge. Nearly every edge in the Seattle dataset has an incline, so the
effect there is small.

### Short steep edges

Both ignore the incline limits on edges of 3 m or less. Twelve edges in the Seattle dataset are over the wheelchair
limit, closed in R5, and walked by Walksheds. The two that were looked at are almost exactly 3 m long, so the
engines probably measure such edges' lengths slightly differently. Not investigated further. It affects 32 of 6,000
wheelchair routes examined.

## Differences in where a route starts and ends

### Attaching a point to the network

Both engines attach a requested point to a nearby edge and start or end the route there. They differ in which
edge, and in where on it. As a result 459 routes (11.5%) start or end more than 5 m apart. Most of those are
otherwise the same route: 269 (6.7%) differ in length by more than 2% for this reason, usually by no more than the
offset itself.

**Where on the edge.** R5 attaches at the point on the edge nearest to the requested point on the ground. Walksheds
finds the nearest point using longitude and latitude as they are, as if a degree of each were the same length. At
Seattle's latitude a degree of longitude is about two-thirds of a degree of latitude, so its point is not the
nearest one: it lies further along the edge, by up to about 10 m.

*Evidence.* Of the 6,986 ends of the sampled routes that both engines find, projecting in longitude and latitude
reproduces Walksheds' attachment point to within 1 m for 6,976. The nearest point on the ground does so for 5,599.

**Which edge.** R5 uses the nearest edge within 50 m that the traveller can use in at least one direction.
Unweaver takes the four nearest edge records, which is usually the two nearest edges since each is stored once per
direction, and uses the first that is usable. If none of those is, it reports an invalid waypoint, even when a
usable edge is a little further away. This rule is read from Unweaver's source and has not been tested against
Walksheds' output.

Of the 410 route ends that the two engines attach more than 5 m apart, 283 are on the same edge at a different
point, and 127 are on different edges.

*Decision.* R5 is left as it is. Its attachment point is the accurate one, and looking further for a usable edge is
why it finds a few routes Walksheds does not. The projection in longitude and latitude is a defect to raise with
the Walksheds team, alongside the incline one.

The 50 m limit is inferred: routes from the deployed Walksheds start up to 50.0 m from the requested point and no
further. Unweaver's own default is 30 m.

### A point at a node

R5 treats a point within 10 cm of a node as being at the node. A point slightly further along an edge is charged
that edge's fixed delay in full (30 s on a crossing), or gets no route if the edge is closed. R5's snapping can
place a point that was given exactly at a node 10 to 30 cm along one of its edges. This affected 15 of 2,210 routes
to node positions in testing, and matters little for real points of interest.

## Differences in what Walksheds finds

### Walksheds sometimes misses a cheaper path

In 42 routes (1.1%), R5's path is cheaper even by Walksheds' own cost function, so Walksheds could have taken
it. In one, Walksheds walks 104 m where a 31 m path exists along edges it
already uses. Not investigated; these look like Walksheds or Unweaver behaviour rather
than something to copy. One known Unweaver difference of this kind: where two OSW edges join the same pair of nodes,
Unweaver keeps one of them and R5 keeps both.

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
| Plain footways | Every `highway=footway` is usable, not only sidewalks and crossings. |
| Steps | Walked at 0.5 m/s, and closed when avoiding curbs. |
| Crossings and incline | Crossings are subject to the incline limits as well as their 30 s delay. |
| Fan mode | With `fanOut` on, streets cost the same as footways whatever the street avoidance. |

## Shared behaviour worth knowing

R5 copies these from Walksheds, so they are not differences, but they affect results:

- **The crossing delay is charged per crossing edge.** Where a dataset splits a crossing at the road's centerline,
  crossing the street costs two delays. The TDEI quality reports' open questions describe this.
- **Starting partway along an edge with a fixed delay costs the whole delay**, as when Unweaver splits an edge.
