"""Pedestrian cost profiles for Unweaver, mirroring R5's com.conveyal.r5.osw.PedestrianCostProfile exactly.

The same profile JSON file drives both engines, so a result from either can be traced to the definition that
produced it (profileId) and to the user inputs (runSpecId). Keep this file and the Java classes in lockstep:
osw-tools/verification/test_pedestrian_profile.py checks the Python side against fixtures that the Java tests also use.

Use from Unweaver: point a profile's "cost_function" at this file and pass the profile path as a static argument:

    {
      "id": "unweaver-dynamic",
      "static": {"profile_path": "/abs/path/to/profiles/unweaver-dynamic.json"},
      "args": [
        {"name": "base_speed", "type": "fields.Number(validate=validate.Range(0, 5))"},
        {"name": "uphill",     "type": "fields.Number(validate=validate.Range(0, 1))"},
        {"name": "downhill",   "type": "fields.Number(validate=validate.Range(0, 1))"},
        {"name": "avoid_curbs", "type": "fields.Boolean()"}
      ],
      "cost_function": "pedestrian_profile.py"
    }

Request arguments other than base_speed and timestamp must be parameters declared by the profile.
"""
import hashlib
import json
import math
import os
import re
from decimal import Decimal

CURB_RAMPS_UNKNOWN = 0
CURB_RAMPS_YES = 1
CURB_RAMPS_NO = 2

WALK_BASE = 1.3

# Keys profiles may not match on (mirrors OswEdgeAttributes.RESERVED_MATCH_KEYS): they vary per edge or exist in only
# one engine's view of an edge, so matching on them would make R5 and Unweaver disagree.
RESERVED_MATCH_KEYS = {"_id", "_u_id", "_v_id", "_u", "_v", "length", "incline", "incline_text", "curbramps",
                       "_layer", "geom", "fid"}


# ---------------------------------------------------------------------------------------------------------------------
# Canonical JSON and identifiers (mirrors CanonicalJson.java)
# ---------------------------------------------------------------------------------------------------------------------

def _canonical_number(x):
    d = Decimal(x) if isinstance(x, int) else Decimal(repr(float(x)))
    if d == 0:
        return "0"
    return format(d.normalize(), "f")


def _canonical_string(s):
    out = ['"']
    for c in s:
        if c == '"':
            out.append('\\"')
        elif c == "\\":
            out.append("\\\\")
        elif c == "\n":
            out.append("\\n")
        elif c == "\r":
            out.append("\\r")
        elif c == "\t":
            out.append("\\t")
        elif c == "\b":
            out.append("\\b")
        elif c == "\f":
            out.append("\\f")
        elif ord(c) < 0x20:
            out.append("\\u%04x" % ord(c))
        else:
            out.append(c)
    out.append('"')
    return "".join(out)


def canonical_json(value):
    """Canonical text of a JSON value: sorted keys, no whitespace, normalized numbers. Identical to the Java output."""
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, (int, float)):
        return _canonical_number(value)
    if isinstance(value, str):
        return _canonical_string(value)
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(canonical_json(v) for v in value) + "]"
    if isinstance(value, dict):
        return "{" + ",".join(
            _canonical_string(k) + ":" + canonical_json(value[k]) for k in sorted(value.keys())
        ) + "}"
    raise TypeError("Not a JSON value: %r" % (value,))


def md5_hex(text):
    return hashlib.md5(text.encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------------------------------------------------
# Profile parsing and parameter resolution (mirrors PedestrianCostProfile.java)
# ---------------------------------------------------------------------------------------------------------------------

def _check_keys(spec, allowed, where):
    for k in spec:
        if k not in allowed:
            raise ValueError("Unknown setting '%s' in %s" % (k, where))


def _number(value, name):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(name + " must be a number.")
    return float(value)


def _bool(value, name):
    if not isinstance(value, bool):
        raise ValueError(name + " must be true or false.")
    return value


def _resolve(value, params):
    if isinstance(value, str) and value.startswith("$"):
        name = value[1:]
        if name not in params:
            raise ValueError("Unknown parameter $" + name)
        return params[name]
    return value


def tag_string(value):
    """String form of a property value, matching how R5's OswReader turns OSW properties into tags."""
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, float):
        # Same as Java's Double.toString for ordinary magnitudes (3.0 -> "3.0"), but exponent forms differ
        # (1e-05 vs 1.0E-5). Prefer string-valued tags in match rules.
        return repr(value)
    if isinstance(value, (dict, list)):
        return json.dumps(value, separators=(",", ":"))
    return str(value)


class _Match:
    def __init__(self, spec):
        self.present = set()
        self.any_of = {}
        for k, v in spec.items():
            if k in RESERVED_MATCH_KEYS:
                raise ValueError("Layers cannot match on '%s'." % k)
            if v == "*":
                self.present.add(k)
            elif isinstance(v, list):
                self.any_of[k] = set(tag_string(x) for x in v)
            elif isinstance(v, (dict,)):
                raise ValueError('Match values must be strings, lists of strings, or "*".')
            else:
                self.any_of[k] = {tag_string(v)}

    @staticmethod
    def _get(tags, key):
        # Unweaver data prepared by prepare_osw_for_unweaver.py has "crossing:markings" as "crossing_markings".
        v = tags.get(key)
        if v is None:
            v = tags.get(re.sub(r"[^0-9A-Za-z_]", "_", key))
        return v

    def matches(self, tags):
        for k in self.present:
            if self._get(tags, k) is None:
                return False
        for k, values in self.any_of.items():
            v = self._get(tags, k)
            if v is None or tag_string(v) not in values:
                return False
        return True


class PedestrianCostProfile:
    def __init__(self, source):
        if not isinstance(source, dict):
            raise ValueError("A profile must be a JSON object.")
        self.source = source
        self.profile_id = md5_hex(canonical_json(source))
        self.name = source.get("name", "unnamed")
        _check_keys(source, {"name", "description", "parameters", "layers", "otherEdges", "schemaVersion"}, "profile")
        self.parameters = {}
        for name, spec in (source.get("parameters") or {}).items():
            _check_keys(spec, {"type", "default", "min", "max", "step", "label", "unit", "description",
                               "sliderMin", "sliderMax", "hidden"}, "parameter " + name)
            ptype = spec.get("type", "number")
            if ptype not in ("number", "boolean"):
                raise ValueError("Parameter %s type must be number or boolean, was %s" % (name, ptype))
            self.parameters[name] = spec
        self.layers = []
        for layer in source.get("layers") or []:
            if layer.get("name") is None:
                raise ValueError("Every layer needs a name.")
            _check_keys(layer, {"name", "description", "match", "inclineSpeed", "delaySeconds", "requireCurbRamps",
                                "speedFactor", "blocked", "avoidance"}, "layer " + layer["name"])
            if "avoidance" in layer:
                _check_keys(layer["avoidance"], {"amount", "k", "unless"}, "avoidance")
                if layer["avoidance"].get("amount") is None:
                    raise ValueError("Missing required setting amount")
            if "inclineSpeed" in layer:
                incline = layer["inclineSpeed"]
                _check_keys(incline, {"maxUphill", "maxDownhill", "ideal", "divisor", "minLengthForLimits"},
                            "inclineSpeed")
                for required in ("maxUphill", "maxDownhill"):
                    if incline.get(required) is None:
                        raise ValueError("Missing required setting " + required)
            self.layers.append(layer)
        other = source.get("otherEdges", "impassable")
        if other not in ("impassable", "walk"):
            raise ValueError("otherEdges must be impassable or walk, was " + str(other))
        self.other_edges = other

    @classmethod
    def from_file(cls, path):
        with open(path, encoding="utf-8") as f:
            return cls(json.load(f))

    def resolve(self, values=None):
        values = dict(values or {})
        for k in values:
            if k not in self.parameters:
                raise ValueError("Profile %s has no parameter named %s" % (self.name, k))
        resolved = {}
        for name, spec in self.parameters.items():
            v = values.get(name)
            if v is None:
                v = spec.get("default")
            if v is None:
                raise ValueError("No value for parameter " + name)
            if spec.get("type", "number") == "boolean":
                if not isinstance(v, bool):
                    raise ValueError("Parameter %s must be true or false." % name)
            else:
                if isinstance(v, bool) or not isinstance(v, (int, float)):
                    raise ValueError("Parameter %s must be a number." % name)
                lo, hi = spec.get("min"), spec.get("max")
                if (lo is not None and v < lo) or (hi is not None and v > hi):
                    raise ValueError("Parameter %s=%s is outside [%s, %s]." % (name, v, lo, hi))
            resolved[name] = v
        return PedestrianCostSpec(self, resolved)


# ---------------------------------------------------------------------------------------------------------------------
# Resolved spec and edge evaluation (mirrors PedestrianCostSpec.java)
# ---------------------------------------------------------------------------------------------------------------------

class _ResolvedLayer:
    def __init__(self, layer, params):
        self.name = layer["name"]
        self.match = _Match(layer.get("match") or {})
        incline = layer.get("inclineSpeed")
        self.has_incline = incline is not None
        if self.has_incline:
            self.max_uphill = _number(_resolve(incline["maxUphill"], params), "maxUphill")
            self.max_downhill = _number(_resolve(incline["maxDownhill"], params), "maxDownhill")
            self.ideal = float(incline.get("ideal", -0.0087))
            divisor = float(incline.get("divisor", 5))
            self.min_length_for_limits = float(incline.get("minLengthForLimits", 3))
            # As in Unweaver's find_k(): speed at the incline limit is base / divisor.
            self.k_up = math.log(divisor) / abs(self.max_uphill - self.ideal)
            self.k_down = math.log(divisor) / abs(-self.max_downhill - self.ideal)
        delay = layer.get("delaySeconds")
        self.delay_seconds = 0.0 if delay is None else _number(_resolve(delay, params), "delaySeconds")
        require = layer.get("requireCurbRamps")
        self.require_curb_ramps = _bool(_resolve(require, params), "requireCurbRamps") if require is not None else False
        factor = layer.get("speedFactor")
        self.speed_factor = 1.0 if factor is None else _number(_resolve(factor, params), "speedFactor")
        blocked = layer.get("blocked")
        self.blocked = _bool(_resolve(blocked, params), "blocked") if blocked is not None else False
        # What the whole cost (travel time and delay) is multiplied by. 1 unless the layer has an avoidance.
        self.cost_factor = 1.0
        avoidance = layer.get("avoidance")
        if avoidance is not None and avoidance.get("unless") is not None \
                and _bool(_resolve(avoidance["unless"], params), "avoidance unless"):
            avoidance = None  # switched off: the layer keeps its ordinary cost
        if avoidance is not None:
            amount = _number(_resolve(avoidance["amount"], params), "avoidance amount")
            if amount >= 1:
                self.blocked = True
            else:
                self.cost_factor = math.exp(float(avoidance.get("k", 1)) * amount)


def _to_float_or_nan(v):
    if v is None or isinstance(v, bool):
        return math.nan
    try:
        return float(v)
    except (TypeError, ValueError):
        return math.nan


def parse_curb_ramps(value):
    """Interpret an explicit curbramps value, as OswEdgeAttributes.parseCurbRamps does in R5."""
    if value is None:
        return CURB_RAMPS_UNKNOWN
    v = tag_string(value).strip().lower()
    if v in ("true", "yes"):
        return CURB_RAMPS_YES
    if v in ("false", "no"):
        return CURB_RAMPS_NO
    try:
        d = float(v)
    except ValueError:
        return CURB_RAMPS_UNKNOWN
    if d == 1:
        return CURB_RAMPS_YES
    if d == 0:
        return CURB_RAMPS_NO
    return CURB_RAMPS_UNKNOWN


def curb_ramp_status(d):
    """Curb ramp status from an edge's "curbramps" property (see prepare_osw_for_unweaver.py)."""
    return parse_curb_ramps(d.get("curbramps"))


class PedestrianCostSpec:
    def __init__(self, profile, parameter_values):
        self.profile = profile
        self.parameter_values = parameter_values
        self.run_spec_id = md5_hex(canonical_json(self.run_spec_json()))
        self.layers = [_ResolvedLayer(layer, parameter_values) for layer in profile.layers]

    def run_spec_json(self):
        return {"profileId": self.profile.profile_id, "parameters": dict(self.parameter_values)}

    def provenance(self):
        return {
            "profileName": self.profile.name,
            "profileId": self.profile.profile_id,
            "runSpecId": self.run_spec_id,
            "parameters": canonical_json(self.parameter_values),
        }

    def layer_for(self, tags):
        for layer in self.layers:
            if layer.match.matches(tags):
                return layer
        return None

    def evaluate(self, tags, incline, length_meters, curb_ramps):
        """Return (speed_factor, delay_seconds); speed_factor is None if the edge is impassable."""
        if tags is None:
            return 1.0, 0.0
        layer = self.layer_for(tags)
        if layer is None:
            return (None, 0.0) if self.profile.other_edges == "impassable" else (1.0, 0.0)
        if layer.blocked:
            return None, 0.0
        factor = layer.speed_factor
        # An edge with no incline is walked at the layer's plain speed, as Walksheds does, not treated as flat.
        if layer.has_incline and not math.isnan(incline):
            i = incline
            if length_meters > layer.min_length_for_limits and (i > layer.max_uphill or i < -layer.max_downhill):
                return None, 0.0
            k = layer.k_up if i > layer.ideal else layer.k_down
            factor *= math.exp(-k * abs(i - layer.ideal))
        if layer.require_curb_ramps and curb_ramps != CURB_RAMPS_YES:
            return None, 0.0
        return factor / layer.cost_factor, layer.delay_seconds * layer.cost_factor

    def edge_seconds(self, d, base_speed):
        """Cost in seconds of an Unweaver edge dict d, or None if impassable. Mirrors R5's exact (unrounded) cost."""
        length = _to_float_or_nan(d.get("length"))
        factor, delay = self.evaluate(d, _to_float_or_nan(d.get("incline")), length, curb_ramp_status(d))
        if factor is None:
            return None
        return length / (base_speed * factor) + delay


# ---------------------------------------------------------------------------------------------------------------------
# Unweaver entry point
# ---------------------------------------------------------------------------------------------------------------------

def _load_profile(profile_path):
    if not os.path.isabs(profile_path):
        profile_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), profile_path)
    return PedestrianCostProfile.from_file(profile_path)


def cost_fun_generator(*graph, profile_path=None, base_speed=WALK_BASE, timestamp=None, **params):
    """Unweaver cost function generator.

    Current Unweaver calls this as cost_fun_generator(G, **request_args) after binding the profile's "static" values;
    the 2019 Unweaver pinned by WalkshedTool calls cost_fun_generator(**request_args). Both are accepted; the graph
    is not used. The profile file is found from (in order): the profile_path static argument, the PEDESTRIAN_PROFILE
    environment variable, or pedestrian_profile.json next to this file. base_speed and the profile's parameters come
    from the request. timestamp is accepted and ignored: elevators are treated as always open, as in R5."""
    if profile_path is None:
        profile_path = os.environ.get("PEDESTRIAN_PROFILE", "pedestrian_profile.json")
    spec = _load_profile(profile_path).resolve({k: v for k, v in params.items() if v is not None})
    base = float(base_speed if base_speed is not None else WALK_BASE)

    def cost_fun(u, v, d):
        return spec.edge_seconds(d, base)

    cost_fun.provenance = spec.provenance()
    return cost_fun


if __name__ == "__main__":
    import sys
    if len(sys.argv) < 2:
        print("Usage: pedestrian_profile.py PROFILE.json ['{\"param\": value}']")
        sys.exit(1)
    p = PedestrianCostProfile.from_file(sys.argv[1])
    s = p.resolve(json.loads(sys.argv[2]) if len(sys.argv) > 2 else {})
    print(json.dumps(s.provenance(), indent=2))
