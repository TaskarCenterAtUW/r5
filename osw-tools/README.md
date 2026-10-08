# OpenSidewalks networks and pedestrian cost profiles

This fork of R5 can build a pedestrian network directly from an [OpenSidewalks](https://github.com/OpenSidewalks/OpenSidewalks-Schema)
(OSW 0.2) dataset and route over it with **user-configurable pedestrian cost functions** ("profiles") that follow the
semantics of Unweaver's `cost-dynamic.py` (WalkshedTool). The same profile file drives Unweaver too, so results from
both engines can be compared, and every result is tagged with identifiers saying exactly what produced it.

## Step-by-step example

The commands below run from the repository root (`~/GitHub/r5`). The example uses the small synthetic OSW dataset
from the tests, so you can check every number against this page before switching to real data.

### 1. Install Java 21 and Gradle

The repository has no Gradle wrapper, so install Gradle (8.14 or newer) yourself. On macOS:

```bash
brew install openjdk@21 gradle
java -version        # should report 21
```

### 2. Run the tests and build the jar

```bash
gradle test --tests 'com.conveyal.r5.osw.*'
gradle shadowJar
ls build/libs/r5-*-all.jar
```

The first command runs the 18 OSW and profile tests. The second builds one jar containing R5 and all of
its dependencies. The examples below find it with a glob:

```bash
R5_JAR=$(ls -t build/libs/r5-*-all.jar | head -1)
```

### 3. Compute a walkshed from one OSW node

```bash
java -cp "$R5_JAR" com.conveyal.r5.osw.OswWalkshedMain \
  --osw src/test/resources/com/conveyal/r5/osw/tiny \
  --profile src/test/resources/com/conveyal/r5/osw/unweaver-dynamic.json \
  --origin-node 2 \
  --max-cost 600 \
  --out walkshed.json
```

After R5's build logging, the last two lines are:

```
Origin 2: 10 nodes reached (exact), 10 (router). Wrote walkshed.json
profileId 6d87a93db06e4151686f7bd7b07ee2bd  runSpecId ef7e971e5b97d19665222263fc63b4e6
```

Options:

| Option | Meaning |
|---|---|
| `--osw` | OSW dataset: a `.zip`, a directory with `*.nodes.geojson` and `*.edges.geojson`, or an `*.edges.geojson` file |
| `--profile` | Pedestrian cost profile JSON |
| `--params` | User inputs as JSON, e.g. `'{"uphill": 0.083}'`. Parameters left out use the profile's defaults. |
| `--walk-speed` | Base walking speed in m/s (default 1.3) |
| `--origin-node` | OSW `_id` of the start node. Or use `--origin-lonlat LON,LAT` to start at the nearest OSW node. |
| `--max-cost` | Walkshed limit in seconds |
| `--out` | Output file |

### 4. Read the output

`walkshed.json` contains:

```jsonc
{
  "provenance": {                       // what produced this result
    "profileName": "unweaver-dynamic",
    "profileId": "6d87a93db06e4151686f7bd7b07ee2bd",   // version of the cost function
    "runSpecId": "ef7e971e5b97d19665222263fc63b4e6",   // cost function + user inputs
    "parameters": "{\"avoid_curbs\":true,\"downhill\":0.1,\"uphill\":0.085}",
    "baseSpeed": 1.3,
    "networkInputMd5": "1f4416a7bfde78098f8d71546e23ca5c",  // the OSW input files
    "r5Version": "...", "r5Commit": "...",
    "engine": "r5"
  },
  "query": { "originNode": "2", "lon": ..., "lat": ..., "maxCost": 600, "parameters": {...} },
  "exact":  [ {"node": "1", "lon": -122.3, "lat": 47.65, "cost": 111.85261519391284}, ... ],
  "router": [ {"node": "1", "lon": -122.3, "lat": 47.65, "cost": 112.0}, ... ],
  "exactReachableEdges": [ {"edge": "101", "u": "2", "v": "1", "layer": "sidewalks"}, ... ],
  "exactPartialEdges":   [ ... edges cut off by the max-cost limit ... ]
}
```

- `exact` gives each reached OSW node's cost in seconds, unrounded. These are the numbers to compare with other tools.
- `router` gives the same walkshed from R5's real router. It works in whole seconds and rounds each edge up, so it
  is a few seconds slower over a path.
- Node and edge IDs are the OSW `_id` values from your data.

In the example, node `3` is missing: the only way there is 10% uphill, past the default 8.5% limit. Node `31` is also
missing, because crossing `111` has a raised kerb.

### 5. Change the user inputs

```bash
java -cp "$R5_JAR" com.conveyal.r5.osw.OswWalkshedMain \
  --osw src/test/resources/com/conveyal/r5/osw/tiny \
  --profile src/test/resources/com/conveyal/r5/osw/unweaver-dynamic.json \
  --params '{"avoid_curbs": false}' \
  --origin-node 2 --max-cost 600 --out walkshed-no-curbs.json
```

```
Origin 2: 12 nodes reached (exact), 12 (router). Wrote walkshed-no-curbs.json
profileId 6d87a93db06e4151686f7bd7b07ee2bd  runSpecId 9b67b0e9d42998b48b5ad67ae19da156
```

The crossings without curb ramps are now usable, so more nodes are reached. The `profileId` is unchanged (same cost
function) but the `runSpecId` differs (different inputs).

### 6. Make your own profile

Copy the example and edit it. Here a profile for cane users gets a shorter crossing delay and a steeper default
uphill limit:

```bash
cp src/test/resources/com/conveyal/r5/osw/unweaver-dynamic.json osw-tools/profiles/cane-user.json
# edit cane-user.json:  "name": "cane-user",  crossings "delaySeconds": 20,  uphill "default": 0.12
java -cp "$R5_JAR" com.conveyal.r5.osw.OswWalkshedMain \
  --osw src/test/resources/com/conveyal/r5/osw/tiny \
  --profile osw-tools/profiles/cane-user.json \
  --origin-node 2 --max-cost 600 --out walkshed-cane.json
```

```
Origin 2: 12 nodes reached (exact), 12 (router). Wrote walkshed-cane.json
profileId d7f2d99fc321e48f6660ceae7679829d  runSpecId cd16093d16740a42eb00b36672a7bdc2
```

Any change to the definition gives a new `profileId`; reformatting the file does not. To find stale results, compare
the `profileId` stored in each result with the current one, which you can print without running a walkshed:

```bash
python3 osw-tools/pedestrian_profile.py osw-tools/profiles/cane-user.json '{"uphill": 0.1}'
```

A profile with a typo (e.g. `"delaySecs"`) or a setting of the wrong type is rejected with an error naming it, not
silently ignored. The format is described under [Profile format](#profile-format) below.

### 7. Run on your own OSW data

Use the dataset as exported (zip or directory). Choose an origin from the nodes file, or start at a coordinate:

```bash
java -cp "$R5_JAR" com.conveyal.r5.osw.OswWalkshedMain \
  --osw ~/data/my-city.osw.zip \
  --profile src/test/resources/com/conveyal/r5/osw/unweaver-dynamic.json \
  --params '{"uphill": 0.083, "downhill": 0.1, "avoid_curbs": true}' \
  --origin-lonlat -122.3035,47.6553 \
  --max-cost 900 --out my-city-walkshed.json
```

With `--origin-lonlat`, R5 prints which OSW node it started from and how far that node is from the coordinate. For a
quick look, load `exactReachableEdges` or the `exact` nodes into QGIS, using the `lon`/`lat` fields as points and
joining edges on their OSW `_id`.

### 8. Use a profile in code or in a request

From Java:

```java
TransportNetwork network = TransportNetwork.fromOsw("my-city.osw.zip", null);
JsonNode profileJson = new ObjectMapper().readTree(new File("src/test/resources/com/conveyal/r5/osw/unweaver-dynamic.json"));

ProfileRequest req = new ProfileRequest();
req.walkSpeed = 1.3f;
req.pedestrianCost = new PedestrianCostRequest(profileJson, Map.of("uphill", 0.083, "avoid_curbs", true));

StreetRouter router = new StreetRouter(network.streetLayer);
router.profileRequest = req;
router.streetMode = StreetMode.WALK;
router.timeLimitSeconds = 900;
router.setOrigin(47.6553, -122.3035);
router.route();
// router.getReachedVertices(): vertex -> seconds.
// network.streetLayer.edgeStore.oswAttributes.oswNodeId(vertex) gives the OSW node ID.
// req.pedestrianCost.spec().provenance() gives the IDs to store with the result.
```

In a JSON request to an R5 worker (an analysis task), add the same thing as a field. The profile goes inline:

```json
"pedestrianCost": {
  "profile": { "...": "contents of unweaver-dynamic.json" },
  "parameters": { "uphill": 0.083, "downhill": 0.1, "avoid_curbs": true }
}
```

It applies to walking only. The network must be built from OSW data; otherwise the request fails with an error
saying so. Building OSW networks through the analysis backend isn't wired up yet (see Known limitations).

### 9. See walksheds on a map

`OswDemoServer` is a small web server, separate from the Conveyal analysis backend and with no database. It loads
one OSW dataset and the profiles in `osw-tools/profiles/`, and serves a map page where you pick a traveller profile,
adjust its limits, and click a starting point.

The OpenSidewalks schema repository includes a real example dataset (about 4,400 edges around the Microsoft campus in
Redmond, WA, with inclines and kerbs):

```bash
git clone --depth 1 https://github.com/OpenSidewalks/OpenSidewalks-Schema.git ~/GitHub/OpenSidewalks-Schema
gradle runOswDemo --args="--osw $HOME/GitHub/OpenSidewalks-Schema/data"
```

Or, from the jar:

```bash
java -cp "$R5_JAR" com.conveyal.r5.osw.OswDemoServer --osw ~/GitHub/OpenSidewalks-Schema/data
```

Open <http://localhost:8080> and click near a sidewalk:

- **Who is travelling** chooses a profile file. The sliders and checkboxes under it come from the profile's
  `parameters`, using their `label`, `unit` (`grade` is shown as a percentage), `min`, `max` and `step`. `sliderMin`
  and `sliderMax` give a slider a narrower range than the values the parameter accepts, and `"hidden": true` keeps a
  parameter off the page, at its default.
- The walkshed is colored by minutes from the start. Dashed magenta marks paths the traveller could use but not with
  the current limits (too steep, no curb ramps). Edges that aren't paths for this traveller at all, like roads for a
  wheelchair user, are drawn faintly.
- **What produced this result** shows the profile ID, run ID, a hash of the network data and the R5 version. Click an
  ID to copy it.
- The page address records the profile, limits, speed, time limit and starting point, so a link reproduces the view.

The demo comes with two profiles (see step 10): `ws-prod.json`, the cost function on the production Walksheds server,
and `walksheds.json`, the same function from before fan mode was added. Add more profile files to
`osw-tools/profiles/` and they appear in the list. The direct port of WalkshedTool's older function is
kept with the tests, at `src/test/resources/com/conveyal/r5/osw/unweaver-dynamic.json`. It only recognizes
`footway=sidewalk` and `footway=crossing`, so on TDEI data many connecting footpaths are unusable with it.

Profile files are read again on every request: edit one, reload the page, and the new profile ID appears. A file with
a mistake shows up in the list as "(has errors)", and `/api/info` gives the message.

### 10. Run the TDEI quality reports against R5

`OswDemoServer` also answers the calls the TDEI quality reports make to the Walksheds service, under `/api/v1`. Start
it with the dataset the reports will ask for, and point the reports at it:

```bash
gradle runOswDemo --args="--osw /path/to/dataset --dataset-id <TDEI dataset id>"
WALKSHEDS_URL=http://localhost:8080/api/v1   # in the reports' environment
```

| Call | What it does here |
|---|---|
| `GET /router/status` | Always `{"status": "ready", "dataset_id": "<--dataset-id>"}`. |
| `GET /router/build?dataset_id=…` | `{"code": "Ok"}` for the loaded dataset. Any other is a 501: the server can't load datasets from TDEI. |
| `GET /router/lock`, `/router/unlock` | Accepted and ignored. |
| `GET /routing/reachable_tree/custom.json?lon&lat&max_cost…` | `edges` (reached edges, cut short where the cost runs out) and `node_costs`. |
| `GET /routing/shortest_path/custom.json?lon1&lat1&lon2&lat2…` | `routes[0]` with `distance`, `duration`, `geometry`, `segments` and `legs`; or `code` `NoPath` / `InvalidWaypoint`. |

The full specification is `src/main/resources/osw-demo/openapi.yaml` (OpenAPI 3). The server shows it as a Swagger
page at `/docs.html`, linked from the bottom of the map page's panel. The container's proxy serves the same page at
`/docs`, without a token.

`uphill`, `downhill`, `avoidCurbs` and `streetAvoidance` set the `uphill`, `downhill`, `avoid_curbs` and
`street_avoidance` parameters of the profile named by `--walksheds-profile` (default `ws-prod`), where it has them.
Costs are at 1.3 m/s.

Where it differs from Walksheds, in brief. [DIFFERENCES.md](DIFFERENCES.md) has the full list, with the evidence for
each and how closely the two agree:

- `reverse` is not implemented. A request that turns it on gets a 501 with `{"error": "Not implemented: reverse"}`.
- `fanOut` ("fan mode") costs streets like pedestrian edges: by length and incline, with no street penalty, whatever
  `streetAvoidance` the request gives. It sets the profile's `fan_out` parameter. `walksheds` has none, so with
  `--walksheds-profile walksheds` a request that turns `fanOut` on gets a 501.
- The `ws-prod` profile follows the production Walksheds server's `cost-custom.py` (October 2026); `walksheds` is the
  same without fan mode (the version on TDEI ticket 1965). In both, every footway is usable; crossings add 30 s and
  need curb ramps when avoiding curbs; steps are walked at 0.5 m/s unless avoiding curbs; streets cost
  `exp(k × streetAvoidance)` times more (k = 2 service, 3 residential, 4 secondary, tertiary and unclassified) and
  are closed at 1. Rules they leave out are listed below.
- Points snap to the nearest edge within 50 m that the traveller can use in at least one direction, and are
  `InvalidWaypoint` if there is none. (50 m is what the deployed Walksheds appears to use; Unweaver's default is 30.)
- Edge features have the OSW properties with `:` in keys replaced by `_`, but not the `curbs`, `lowered_curbs` and
  `flush_curbs` counts, and `_u` / `_v` have no elevation part.
- Times are R5's, with each edge rounded up to a whole second.

Rules in the Walksheds cost function that the profiles leave out, and why:

| Rule | Why it is left out |
|---|---|
| Reverse walksheds (`reverseWalkshed` swaps the uphill and downhill limits) | The API has no reverse search yet. |
| Sidewalk scoring (`sidewalkScore`: only streets are usable, at plain cost) | Needs `avoidance`'s `unless` to take a second switch besides fan mode. |
| Avoiding primary streets (`avoidPrimaryStreet`) | It reads `street_highway`, the class of street a sidewalk runs along, which Walksheds derives and TDEI data does not have. It would also have to apply across layers. |
| Indoor footways' opening hours | The format has no notion of time of day. |
| Transit edges (`railway`, `bus`, `flex`) and their avoid switches | Not in the datasets tested; the quality reports never send the switches. |
| Closed edges (`is_closed`) | Not in the datasets tested. |
| Pathway stairs and escalators (`pathway_mode` 2 or 4, closed when avoiding curbs) | Not in the datasets tested. |

Options: `--profiles DIR` (default `osw-tools/profiles`), `--port` (default 8080), and `--static DIR`, which serves the
page from a folder instead of the jar, so you can edit `src/main/resources/osw-demo/` and just reload.

## Pieces

| Where | What |
|---|---|
| `com.conveyal.r5.osw.OswReader` | Reads OSW `*.nodes.geojson` + `*.edges.geojson` (zip, directory, or edges file). One OSW edge → one R5 edge pair; OSW node/edge IDs preserved. |
| `TransportNetwork.fromOsw(path, config)` | Builds a walk-only network from OSW. Uses the `osw` permission labeler; records OSW attributes per edge (`OswEdgeAttributes`). |
| `PedestrianCostProfile` / `PedestrianCostSpec` | The profile format, parameter validation, md5 identifiers, and the per-edge cost function. |
| `ProfileRequest.pedestrianCost` | `{"profile": {...}, "parameters": {...}}` on any request applies the profile to WALK. Base speed = `walkSpeed`. |
| `OswWalkshedMain` | Command-line walkshed from one OSW node, for validation. |
| `OswDemoServer` + `src/main/resources/osw-demo/` | The demo map (step 9). |
| `WalkshedsApi` + `osw-tools/profiles/ws-prod.json`, `walksheds.json` | The Walksheds-compatible API (step 10). |
| `osw-tools/pedestrian_profile.py` | The same profile evaluated in Python, usable directly as an Unweaver cost function. |
| `osw-tools/prepare_osw_for_unweaver.py` | Turns an OSW dataset into an Unweaver layer with the same node IDs, lengths and curb-ramp flags R5 uses. |
| `osw-tools/verification/query_unweaver.py`, `compare_walksheds.py`, `validate.sh` | Run Unweaver in-process, compare outputs, or do it all in one go. |

## Profile format

```json
{
  "schemaVersion": 1,
  "name": "unweaver-dynamic",
  "parameters": {
    "uphill":      {"type": "number",  "default": 0.085, "min": 0, "max": 1},
    "downhill":    {"type": "number",  "default": 0.1,   "min": 0, "max": 1},
    "avoid_curbs": {"type": "boolean", "default": true}
  },
  "layers": [
    {"name": "sidewalks", "match": {"footway": "sidewalk"},
     "inclineSpeed": {"maxUphill": "$uphill", "maxDownhill": "$downhill", "ideal": -0.0087, "divisor": 5, "minLengthForLimits": 3}},
    {"name": "crossings", "match": {"footway": "crossing"}, "delaySeconds": 30, "requireCurbRamps": "$avoid_curbs"},
    {"name": "elevator_paths", "match": {"highway": "elevator"}, "delaySeconds": 45}
  ],
  "otherEdges": "impassable"
}
```

- Each edge belongs to the **first layer whose `match` it satisfies**: each key must equal the value, be one of a
  list of values, or be present (`"*"`). Edges matching no layer follow `otherEdges` (`impassable` or `walk`).
  Edges with no OSW attributes at all (transit links, scenario-added streets) are walked at base speed.
- `inclineSpeed`: Tobler's hiking function, with speed falling to `base / divisor` at the user's limits. Edges longer
  than `minLengthForLimits` metres that are steeper than the limits are impassable. An edge with a missing or
  non-numeric `incline` is walked at the layer's plain speed, with no limit applied. `direction` says which incline
  an edge walked against the way it is mapped has: `"travel"` (the default) reverses its sign, so uphill becomes
  downhill; `"mapped"` uses it as mapped, so the edge costs the same both ways. The Walksheds profiles use
  `"mapped"`, because the Walksheds service does.
- `delaySeconds` is added to travel time. (In `cost-dynamic.py` the delay is overwritten. That bug is fixed here.)
- `requireCurbRamps`: the edge needs curb ramps. R5 derives this from the OSW kerb nodes at its two ends, as the
  TDEI Walksheds service does: the edge has ramps unless a kerb stands in the way at either end, meaning any kerb
  that is not `kerb=lowered` or `kerb=flush`, including one with no `kerb` value. An end with no kerb node is no
  obstacle. An explicit `curbramps` edge property takes precedence.
- `speedFactor` (optional) multiplies the speed for a layer.
- `blocked` (optional, true or false) makes the layer impassable. With a parameter, a traveller's choice can close a
  layer: `"blocked": "$avoid_curbs"` on steps.
- `avoidance` (optional) makes a layer cost more the more the traveller wants to avoid it:
  `{"amount": "$street_avoidance", "k": 3}` multiplies the layer's whole cost, delay included, by `exp(k × amount)`,
  and makes it impassable when the amount is 1 or more. `k` defaults to 1. An optional `"unless": "$fan_out"`
  switches the avoidance off when true, leaving the layer's ordinary cost.
- Any setting can be a literal or a `"$parameter"` reference. Unknown or misspelled settings are rejected. Settings
  must have the right JSON type (`requireCurbRamps: "false"` is an error, not true). Layers cannot match on per-edge
  keys such as `incline`, `length` or `curbramps`.
- Elevator `opening_hours` are ignored: elevators are always open.

## Identifiers (provenance)

- **profileId**: md5 of the profile's canonical JSON (sorted keys, no whitespace, normalized numbers). It changes when
  the definition changes, and not when the file is only reformatted.
- **runSpecId**: md5 of canonical `{"profileId": ..., "parameters": {every parameter, defaults filled in}}`. Same
  cost function and same user inputs give the same ID.
- R5 also reports the R5 version and commit, and `OswWalkshedMain` reports an md5 of the OSW input
  (`networkInputMd5`).

Java (`CanonicalJson`) and Python (`canonical_json`) produce identical text, so both engines report the same IDs. The
tests on both sides assert the same expected values. A result is stale when its profileId no longer matches the
current profile file, its parameters changed, or the network or R5 version changed. R5 logs the IDs whenever it
evaluates a profile over a network.

## Validating against Unweaver

Requirements: an R5 jar (`gradle shadowJar` → `build/libs/r5-*-all.jar`), a checkout of
[Unweaver](https://github.com/nbolten/unweaver) (current master) with its dependencies, and SpatiaLite
(`brew install libspatialite`, or `apt install libsqlite3-mod-spatialite`). Unweaver's dependencies need Python ≤ 3.11.
On Python 3.10+, Unweaver itself needs `from collections import MutableMapping` changed to `collections.abc`.

```bash
UNWEAVER_DIR=~/GitHub/unweaver UNWEAVER_PYTHON=~/venvs/unweaver/bin/python \
  osw-tools/verification/validate.sh my-city.osw.zip src/test/resources/com/conveyal/r5/osw/unweaver-dynamic.json <ORIGIN_NODE_ID> 900 \
  '{"uphill": 0.083, "downhill": 0.1, "avoid_curbs": true}' 1.3
```

This prepares the dataset for Unweaver, builds its graph (`--changes-sign incline`, which suits profiles whose
`inclineSpeed.direction` is `"travel"`), queries Unweaver's
`shortest_path_tree` and `reachable_tree` at the origin node, runs `OswWalkshedMain`, and compares them:

1. **Provenance**: same profileId, runSpecId and base speed on both sides.
2. **Exact node costs**: R5's unrounded Dijkstra (double precision, OSW lengths) against Unweaver's node costs. This
   should be identical: on the test fixture the difference is 0 s across every origin and parameter set tried.
3. **Router node costs** (reported, not pass/fail): R5's real router works in whole seconds and rounds each edge up,
   so it is slower than Unweaver by up to about a second per edge on the path.
4. **Reachable edges**: Unweaver's `reachable_tree` edges against R5's fully or partially reachable edges.

To use a profile with Unweaver directly, add `"static": {"profile_path": "/abs/path/profile.json"}` to the Unweaver
profile and set `"cost_function": "pedestrian_profile.py"` (see the docstring in `pedestrian_profile.py`).
`pedestrian_profile.py` also accepts the calling convention of the 2019 Unweaver that WalkshedTool pins. That version
has no `static` arguments, so set `PEDESTRIAN_PROFILE` or put `pedestrian_profile.json` next to the file.

## Known limitations

- **Walk only.** Bike and car are unaffected. In a bike search on an OSW network, walked segments use the profile.
- **Whole seconds.** R5 accumulates whole seconds per edge (see above). The exact comparison bypasses this.
- **Analysis backend not wired yet.** `fromOsw` is used by tests and `OswWalkshedMain`. Uploading OSW through the
  backend, choosing profiles in the UI, and stamping profile/run-spec IDs into regional result metadata are still
  to do. Requests to a worker can already carry `pedestrianCost`.
- **Island pruning.** `fromOsw(path, null)` keeps small disconnected components, as Unweaver does. A config you pass
  in is used as given (`pruneIslands` defaults to true there).
- **Partial edges** at origins and destinations are costed in proportion to geometric distance along the edge, plus
  any layer delay in full, as Unweaver does when it splits an edge.
- **Tag matching** compares strings. Numeric property values are written the same way by both engines for ordinary
  magnitudes, but not for exponent forms (`1e-05` vs `1.0E-5`), so prefer string-valued tags in match rules.
- **Parallel edges in Unweaver.** When two OSW edges join the same pair of nodes, Unweaver keeps only one of them; R5
  keeps both. `compare_walksheds.py` reports these edges separately instead of failing.
- OSW edges without a `highway` tag, or with `highway=construction`/`proposed`, are not routable in R5.

## Tests

- Java: `com.conveyal.r5.osw.PedestrianCostProfileTest` and `OswNetworkTest`, run with the normal Gradle test task.
  They use the fixture in `src/test/resources/com/conveyal/r5/osw/tiny/` (regenerate with `osw-tools/verification/make_test_fixture.py`).
- Python: `cd osw-tools/verification && python3 -m unittest test_pedestrian_profile` (no Unweaver needed).
- `WalkshedsRegressionTest`, also in the Gradle test task, compares R5 with answers saved from the TDEI Walksheds
  service and fails on any difference that [DIFFERENCES.md](DIFFERENCES.md) does not account for. Its fixtures are
  under `src/test/resources/com/conveyal/r5/osw/walksheds/` (rebuild with
  `osw-tools/verification/make_walksheds_fixtures.py`).
