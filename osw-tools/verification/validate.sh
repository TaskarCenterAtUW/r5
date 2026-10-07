#!/usr/bin/env bash
# Run Unweaver and R5 on the same OSW dataset with the same pedestrian cost profile and compare the walksheds.
#
#   osw-tools/verification/validate.sh DATASET PROFILE.json ORIGIN_NODE_ID MAX_COST_SECONDS ['{"uphill":0.083}'] [BASE_SPEED]
#
# Environment:
#   R5_JAR        R5 jar with dependencies (default: newest build/libs/*-all.jar; build it with `gradle shadowJar`)
#   UNWEAVER_DIR  checkout of github.com/nbolten/unweaver (current master), importable by UNWEAVER_PYTHON
#   UNWEAVER_PYTHON  Python with Unweaver's dependencies installed (default: python3)
#   WORK_DIR      where to put the Unweaver project and results (default: ./osw-validation)
set -euo pipefail

DATASET=$1; PROFILE=$2; ORIGIN=$3; MAX_COST=$4; PARAMS=${5:-"{}"}; BASE=${6:-1.3}
HERE=$(cd "$(dirname "$0")" && pwd)
TOOLS=$(cd "$HERE/.." && pwd)
REPO=$(cd "$TOOLS/.." && pwd)
R5_JAR=${R5_JAR:-$(ls -t "$REPO"/build/libs/*-all.jar 2>/dev/null | head -1)}
UNWEAVER_PYTHON=${UNWEAVER_PYTHON:-python3}
WORK_DIR=${WORK_DIR:-osw-validation}
PROFILE=$(cd "$(dirname "$PROFILE")" && pwd)/$(basename "$PROFILE")

[ -n "$R5_JAR" ] || { echo "Set R5_JAR or run 'gradle shadowJar' first."; exit 1; }
[ -n "${UNWEAVER_DIR:-}" ] || { echo "Set UNWEAVER_DIR to a checkout of github.com/nbolten/unweaver."; exit 1; }

PROJECT="$WORK_DIR/unweaver-project"
mkdir -p "$PROJECT"
python3 "$TOOLS/prepare_osw_for_unweaver.py" "$DATASET" "$PROJECT"
cp "$TOOLS/pedestrian_profile.py" "$PROJECT/"
PROFILE_ID=$(basename "$PROFILE" .json)
cat > "$PROJECT/profile-$PROFILE_ID.json" <<EOF
{
  "id": "$PROFILE_ID",
  "static": {"profile_path": "$PROFILE"},
  "args": [
    {"name": "base_speed", "type": "fields.Number(validate=validate.Range(0, 5))"},
$(python3 -c "
import json, sys
p = json.load(open(sys.argv[1]))
rows = []
for name, spec in p.get('parameters', {}).items():
    t = 'fields.Boolean()' if spec.get('type') == 'boolean' else 'fields.Number()'
    rows.append('    {\"name\": \"%s\", \"type\": \"%s\"}' % (name, t))
print(',\n'.join(rows))" "$PROFILE")
  ],
  "cost_function": "pedestrian_profile.py"
}
EOF
rm -f "$PROJECT/graph.gpkg"
(cd "$PROJECT" && PYTHONPATH="$UNWEAVER_DIR" "$UNWEAVER_PYTHON" -c "from unweaver.cli import unweaver; unweaver()" \
    build . --changes-sign incline)

# Query Unweaver at the origin node's coordinates.
read -r LON LAT < <(python3 -c "
import sys; sys.path.insert(0, '$TOOLS')
import prepare_osw_for_unweaver as p
nodes, _ = p._read_dataset(sys.argv[1])
for f in nodes:
    if str(f['properties'].get('_id')) == sys.argv[2]:
        print(*f['geometry']['coordinates'][:2]); break
else:
    sys.exit('Origin node ' + sys.argv[2] + ' not found in the OSW nodes file')" "$DATASET" "$ORIGIN")
UW_ARGS=$(python3 -c "import json,sys; p=json.loads(sys.argv[1]); p['base_speed']=float(sys.argv[2]); print(json.dumps(p))" "$PARAMS" "$BASE")
PYTHONPATH="$UNWEAVER_DIR:$TOOLS" "$UNWEAVER_PYTHON" "$HERE/query_unweaver.py" "$PROJECT" "$PROFILE_ID" "$LON" "$LAT" \
    "$MAX_COST" "$WORK_DIR/unweaver.json" "$UW_ARGS"

java -cp "$R5_JAR" com.conveyal.r5.osw.OswWalkshedMain --osw "$DATASET" --profile "$PROFILE" --params "$PARAMS" \
    --walk-speed "$BASE" --origin-node "$ORIGIN" --max-cost "$MAX_COST" --out "$WORK_DIR/r5.json"

python3 "$HERE/compare_walksheds.py" "$WORK_DIR/r5.json" "$WORK_DIR/unweaver.json" --report "$WORK_DIR/report.json"
