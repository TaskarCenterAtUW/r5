package com.conveyal.r5.osw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A user-configurable pedestrian cost function, expressed as data rather than code so that the same definition can
 * be evaluated identically by R5 (in Java) and by Unweaver (via osw-tools/pedestrian_profile.py), and so that every
 * result can be traced to the exact definition that produced it.
 *
 * The semantics follow Unweaver's cost-dynamic.py (WalkshedTool): each edge is assigned to the first layer whose
 * "match" rules it satisfies. Edges in a layer with an "inclineSpeed" block move at Tobler's hiking function speed,
 * tuned so that speed falls to baseSpeed / divisor at the user's uphill / downhill limits, and are impassable beyond
 * those limits. Layers can add a fixed delay (e.g. waiting to cross) and can require curb ramps.
 *
 * Cost of an edge, in seconds:  length / (baseSpeed * speedFactor) + delaySeconds,  or impassable.
 * baseSpeed comes from the request's walkSpeed.
 *
 * Example:
 * <pre>
 * {
 *   "name": "unweaver-dynamic",
 *   "parameters": {
 *     "uphill":     {"type": "number",  "default": 0.083, "min": 0, "max": 1},
 *     "downhill":   {"type": "number",  "default": 0.1,   "min": 0, "max": 1},
 *     "avoidCurbs": {"type": "boolean", "default": true}
 *   },
 *   "layers": [
 *     {"name": "sidewalks", "match": {"footway": "sidewalk"},
 *      "inclineSpeed": {"maxUphill": "$uphill", "maxDownhill": "$downhill"}},
 *     {"name": "crossings", "match": {"footway": "crossing"},
 *      "delaySeconds": 30, "requireCurbRamps": "$avoidCurbs"},
 *     {"name": "elevator_paths", "match": {"highway": "elevator"}, "delaySeconds": 45}
 *   ],
 *   "otherEdges": "impassable"
 * }
 * </pre>
 *
 * Any numeric or boolean setting may be a literal or a "$parameter" reference. Parameters may also carry "label",
 * "unit" (e.g. "grade", shown as a percentage), "step" and "description", which user interfaces use for display.
 *
 * The profile ID is the md5 of the canonical form of the profile JSON (see {@link CanonicalJson}): it identifies the
 * definition independent of formatting, and changes whenever any part of the definition changes.
 */
public class PedestrianCostProfile {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum OtherEdges { IMPASSABLE, WALK }

    /** The JSON this profile was parsed from, retained for provenance. */
    public final JsonNode source;

    /** md5 of the canonical JSON of the whole profile definition. */
    public final String profileId;

    public final String name;

    public final Map<String, Parameter> parameters;

    public final List<Layer> layers;

    public final OtherEdges otherEdges;

    public static class Parameter {
        public final String name;
        public final boolean isBoolean;
        public final JsonNode defaultValue;
        public final Double min;
        public final Double max;

        Parameter (String name, JsonNode spec) {
            this.name = name;
            String type = text(spec, "type", "number");
            if (!type.equals("number") && !type.equals("boolean")) {
                throw new IllegalArgumentException("Parameter " + name + " type must be number or boolean, was " + type);
            }
            this.isBoolean = type.equals("boolean");
            this.defaultValue = spec.get("default");
            this.min = spec.has("min") ? spec.get("min").asDouble() : null;
            this.max = spec.has("max") ? spec.get("max").asDouble() : null;
            checkKeys(spec, Set.of("type", "default", "min", "max", "step", "label", "unit", "description",
                    "sliderMin", "sliderMax", "hidden"), "parameter " + name);
        }
    }

    /** A value that is either a literal or a reference to a parameter ("$name"). */
    public static class Value {
        final JsonNode literal;
        final String parameter;

        Value (JsonNode node) {
            if (node.isTextual() && node.textValue().startsWith("$")) {
                this.parameter = node.textValue().substring(1);
                this.literal = null;
            } else {
                this.parameter = null;
                this.literal = node;
            }
        }

        JsonNode resolve (Map<String, JsonNode> params) {
            if (parameter == null) return literal;
            JsonNode v = params.get(parameter);
            if (v == null) throw new IllegalArgumentException("Unknown parameter $" + parameter);
            return v;
        }
    }

    /** Matches an edge's tags: every key must match. A value may be a string, a list of strings, or "*" (present). */
    public static class Match {
        final Map<String, Set<String>> anyOf = new LinkedHashMap<>();
        final Set<String> present = new HashSet<>();

        Match (JsonNode spec) {
            for (Iterator<Map.Entry<String, JsonNode>> it = spec.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (OswEdgeAttributes.RESERVED_MATCH_KEYS.contains(e.getKey())) {
                    throw new IllegalArgumentException("Layers cannot match on '" + e.getKey() + "'.");
                }
                JsonNode v = e.getValue();
                if (v.isTextual() && v.textValue().equals("*")) {
                    present.add(e.getKey());
                } else if (v.isArray()) {
                    Set<String> values = new HashSet<>();
                    v.forEach(x -> values.add(x.asText()));
                    anyOf.put(e.getKey(), values);
                } else if (v.isValueNode()) {
                    anyOf.put(e.getKey(), Set.of(v.asText()));
                } else {
                    throw new IllegalArgumentException("Match values must be strings, lists of strings, or \"*\".");
                }
            }
        }

        public boolean matches (Map<String, String> tags) {
            for (String key : present) {
                if (!tags.containsKey(key)) return false;
            }
            for (Map.Entry<String, Set<String>> e : anyOf.entrySet()) {
                String v = tags.get(e.getKey());
                if (v == null || !e.getValue().contains(v)) return false;
            }
            return true;
        }
    }

    public static class InclineSpeed {
        final Value maxUphill;
        final Value maxDownhill;
        /** Grade at which Tobler's function gives the highest speed. -0.0087 in Tobler's hiking function. */
        final double ideal;
        /** Speed at the user's incline limit is baseSpeed / divisor. */
        final double divisor;
        /** The incline limits only exclude edges longer than this (meters), tolerating short steep segments. */
        final double minLengthForLimits;

        InclineSpeed (JsonNode spec) {
            this.maxUphill = new Value(require(spec, "maxUphill"));
            this.maxDownhill = new Value(require(spec, "maxDownhill"));
            this.ideal = spec.path("ideal").asDouble(-0.0087);
            this.divisor = spec.path("divisor").asDouble(5);
            this.minLengthForLimits = spec.path("minLengthForLimits").asDouble(3);
            checkKeys(spec, Set.of("maxUphill", "maxDownhill", "ideal", "divisor", "minLengthForLimits"), "inclineSpeed");
        }
    }

    /**
     * Makes a layer's edges cost more the more the traveller wants to avoid them, as Walksheds does for streets: the
     * cost is multiplied by exp(k * amount), and at an amount of 1 or more the edges are impassable. "unless" (true
     * or false) switches the avoidance off, leaving the layer's ordinary cost: Walksheds' fan mode.
     */
    public static class Avoidance {
        final Value amount;
        final double k;
        final Value unless;

        Avoidance (JsonNode spec) {
            this.amount = new Value(require(spec, "amount"));
            this.k = spec.path("k").asDouble(1);
            this.unless = spec.has("unless") ? new Value(spec.get("unless")) : null;
            checkKeys(spec, Set.of("amount", "k", "unless"), "avoidance");
        }
    }

    public static class Layer {
        public final String name;
        final Match match;
        final InclineSpeed inclineSpeed;
        final Value delaySeconds;
        final Value requireCurbRamps;
        final Value speedFactor;
        final Value blocked;
        final Avoidance avoidance;

        Layer (JsonNode spec) {
            this.name = text(spec, "name", null);
            if (name == null) throw new IllegalArgumentException("Every layer needs a name.");
            this.match = new Match(spec.path("match").isObject() ? spec.get("match") : MAPPER.createObjectNode());
            this.inclineSpeed = spec.has("inclineSpeed") ? new InclineSpeed(spec.get("inclineSpeed")) : null;
            this.delaySeconds = spec.has("delaySeconds") ? new Value(spec.get("delaySeconds")) : null;
            this.requireCurbRamps = spec.has("requireCurbRamps") ? new Value(spec.get("requireCurbRamps")) : null;
            this.speedFactor = spec.has("speedFactor") ? new Value(spec.get("speedFactor")) : null;
            this.blocked = spec.has("blocked") ? new Value(spec.get("blocked")) : null;
            this.avoidance = spec.has("avoidance") ? new Avoidance(spec.get("avoidance")) : null;
            checkKeys(spec, Set.of("name", "description", "match", "inclineSpeed", "delaySeconds", "requireCurbRamps",
                    "speedFactor", "blocked", "avoidance"), "layer " + name);
        }
    }

    public PedestrianCostProfile (JsonNode source) {
        if (source == null || !source.isObject()) throw new IllegalArgumentException("A profile must be a JSON object.");
        this.source = source.deepCopy();
        this.profileId = CanonicalJson.md5OfCanonical(source);
        this.name = text(source, "name", "unnamed");
        Map<String, Parameter> params = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = source.path("parameters").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            params.put(e.getKey(), new Parameter(e.getKey(), e.getValue()));
        }
        this.parameters = Collections.unmodifiableMap(params);
        List<Layer> layerList = new ArrayList<>();
        for (JsonNode layer : source.path("layers")) layerList.add(new Layer(layer));
        this.layers = Collections.unmodifiableList(layerList);
        String other = text(source, "otherEdges", "impassable");
        this.otherEdges = switch (other) {
            case "impassable" -> OtherEdges.IMPASSABLE;
            case "walk" -> OtherEdges.WALK;
            default -> throw new IllegalArgumentException("otherEdges must be impassable or walk, was " + other);
        };
        checkKeys(source, Set.of("name", "description", "parameters", "layers", "otherEdges", "schemaVersion"), "profile");
    }

    public static PedestrianCostProfile fromFile (File file) throws IOException {
        return new PedestrianCostProfile(MAPPER.readTree(file));
    }

    public static PedestrianCostProfile fromJson (String json) throws IOException {
        return new PedestrianCostProfile(MAPPER.readTree(json));
    }

    /**
     * Combine this profile with user-supplied parameter values, validating them and filling in defaults.
     * @param values parameter values by name; may be null or omit parameters that have defaults.
     */
    public PedestrianCostSpec resolve (Map<String, ?> values) {
        ObjectNode supplied = values == null ? MAPPER.createObjectNode() : MAPPER.valueToTree(values);
        for (Iterator<String> it = supplied.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!parameters.containsKey(k)) {
                throw new IllegalArgumentException("Profile " + name + " has no parameter named " + k);
            }
        }
        Map<String, JsonNode> resolved = new LinkedHashMap<>();
        for (Parameter p : parameters.values()) {
            JsonNode v = supplied.has(p.name) && !supplied.get(p.name).isNull() ? supplied.get(p.name) : p.defaultValue;
            if (v == null || v.isNull()) throw new IllegalArgumentException("No value for parameter " + p.name);
            if (p.isBoolean && !v.isBoolean()) {
                throw new IllegalArgumentException("Parameter " + p.name + " must be true or false.");
            }
            if (!p.isBoolean) {
                if (!v.isNumber()) throw new IllegalArgumentException("Parameter " + p.name + " must be a number.");
                double d = v.asDouble();
                if ((p.min != null && d < p.min) || (p.max != null && d > p.max)) {
                    throw new IllegalArgumentException(String.format(
                            "Parameter %s=%s is outside [%s, %s].", p.name, d, p.min, p.max));
                }
            }
            resolved.put(p.name, v);
        }
        return new PedestrianCostSpec(this, resolved);
    }

    private static JsonNode require (JsonNode spec, String key) {
        JsonNode v = spec.get(key);
        if (v == null || v.isNull()) throw new IllegalArgumentException("Missing required setting " + key);
        return v;
    }

    private static String text (JsonNode spec, String key, String defaultValue) {
        JsonNode v = spec.get(key);
        return (v == null || v.isNull()) ? defaultValue : v.asText();
    }

    /** Reject misspelled settings, which would otherwise be silently ignored but still change the profile ID. */
    private static void checkKeys (JsonNode spec, Set<String> allowed, String where) {
        for (Iterator<String> it = spec.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!allowed.contains(k)) throw new IllegalArgumentException("Unknown setting '" + k + "' in " + where);
        }
    }
}
