package com.conveyal.r5.osw;

import com.conveyal.r5.SoftwareVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A {@link PedestrianCostProfile} combined with specific parameter values: everything needed to cost an edge.
 *
 * Identifiers for provenance:
 *  - profileId: md5 of the canonical profile definition. Which version of the cost function was used.
 *  - runSpecId: md5 of the canonical {"parameters": {...resolved values...}, "profileId": ...}. Which cost function
 *    AND which user inputs. Two results with the same runSpecId (and the same network and R5 version) are comparable.
 * See {@link #provenance()} for the full set of identifiers that should be attached to results.
 */
public class PedestrianCostSpec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public final PedestrianCostProfile profile;

    /** Every parameter's value, with defaults filled in, in the order declared by the profile. */
    public final Map<String, JsonNode> parameterValues;

    public final String runSpecId;

    private final ResolvedLayer[] layers;

    /** Output of {@link #evaluate}: the edge is impassable for this spec. */
    public static final double IMPASSABLE = Double.NaN;

    PedestrianCostSpec (PedestrianCostProfile profile, Map<String, JsonNode> parameterValues) {
        this.profile = profile;
        this.parameterValues = Collections.unmodifiableMap(new LinkedHashMap<>(parameterValues));
        this.runSpecId = CanonicalJson.md5OfCanonical(runSpecJson());
        this.layers = new ResolvedLayer[profile.layers.size()];
        for (int i = 0; i < layers.length; i++) {
            layers[i] = new ResolvedLayer(profile.layers.get(i), parameterValues);
        }
    }

    /** The JSON object whose canonical md5 is the runSpecId. */
    public ObjectNode runSpecJson () {
        ObjectNode spec = MAPPER.createObjectNode();
        spec.put("profileId", profile.profileId);
        ObjectNode params = spec.putObject("parameters");
        parameterValues.forEach(params::set);
        return spec;
    }

    /**
     * Identifiers to attach to every result produced with this spec, so it is clear what produced the result and
     * whether it is stale. The network ID is not known here and should be added by the caller.
     */
    public Map<String, String> provenance () {
        Map<String, String> p = new TreeMap<>();
        p.put("profileName", profile.name);
        p.put("profileId", profile.profileId);
        p.put("runSpecId", runSpecId);
        p.put("parameters", CanonicalJson.canonicalize(MAPPER.valueToTree(parameterValues)));
        p.put("r5Version", SoftwareVersion.instance.version);
        p.put("r5Commit", SoftwareVersion.instance.commit);
        return p;
    }

    private static class ResolvedLayer {
        final String name;
        final PedestrianCostProfile.Match match;
        final boolean hasIncline;
        final boolean inclineAsMapped;
        final double maxUphill, maxDownhill, ideal, kUp, kDown, minLengthForLimits;
        final double delaySeconds;
        final boolean requireCurbRamps;
        final double speedFactor;
        final boolean blocked;
        /** What the whole cost (travel time and delay) is multiplied by. 1 unless the layer has an avoidance. */
        final double costFactor;

        ResolvedLayer (PedestrianCostProfile.Layer layer, Map<String, JsonNode> params) {
            this.name = layer.name;
            this.match = layer.match;
            this.hasIncline = layer.inclineSpeed != null;
            this.inclineAsMapped = hasIncline && layer.inclineSpeed.asMapped;
            if (hasIncline) {
                PedestrianCostProfile.InclineSpeed s = layer.inclineSpeed;
                maxUphill = number(s.maxUphill.resolve(params), "maxUphill");
                maxDownhill = number(s.maxDownhill.resolve(params), "maxDownhill");
                ideal = s.ideal;
                minLengthForLimits = s.minLengthForLimits;
                // As in Unweaver's find_k(): choose k so that speed at the incline limit is base / divisor.
                kUp = Math.log(s.divisor) / Math.abs(maxUphill - ideal);
                kDown = Math.log(s.divisor) / Math.abs(-maxDownhill - ideal);
            } else {
                maxUphill = maxDownhill = ideal = kUp = kDown = minLengthForLimits = 0;
            }
            this.delaySeconds = layer.delaySeconds == null ? 0 : number(layer.delaySeconds.resolve(params), "delaySeconds");
            this.requireCurbRamps = layer.requireCurbRamps != null
                    && bool(layer.requireCurbRamps.resolve(params), "requireCurbRamps");
            this.speedFactor = layer.speedFactor == null ? 1 : number(layer.speedFactor.resolve(params), "speedFactor");
            boolean blocked = layer.blocked != null && bool(layer.blocked.resolve(params), "blocked");
            double costFactor = 1;
            boolean avoid = layer.avoidance != null && !(layer.avoidance.unless != null
                    && bool(layer.avoidance.unless.resolve(params), "avoidance unless"));
            if (avoid) {
                double amount = number(layer.avoidance.amount.resolve(params), "avoidance amount");
                if (amount >= 1) blocked = true;
                else costFactor = Math.exp(layer.avoidance.k * amount);
            }
            this.blocked = blocked;
            this.costFactor = costFactor;
        }

        /** Settings must have the right JSON type, so that Java and Python cannot interpret them differently. */
        private static double number (JsonNode v, String name) {
            if (!v.isNumber()) throw new IllegalArgumentException(name + " must be a number.");
            return v.asDouble();
        }

        private static boolean bool (JsonNode v, String name) {
            if (!v.isBoolean()) throw new IllegalArgumentException(name + " must be true or false.");
            return v.booleanValue();
        }
    }

    /**
     * Evaluate the cost function for one directed edge.
     *
     * @param tags the edge's OSW tags (null for edges not derived from OSW data, which are walked at base speed)
     * @param incline grade in the direction of travel, NaN if unknown (the edge is then walked at its plain speed)
     * @param lengthMeters edge length, used only for the minimum length below which incline limits are not applied
     * @param curbRamps one of the OswEdgeAttributes.CURB_RAMPS_* constants
     * @param out receives {speedFactor, delaySeconds}. speedFactor multiplies the base walk speed; it is
     *            {@link #IMPASSABLE} (NaN) if the edge cannot be traversed.
     */
    public void evaluate (Map<String, String> tags, double incline, double lengthMeters, byte curbRamps, double[] out) {
        evaluate(tags, incline, incline, lengthMeters, curbRamps, out);
    }

    /**
     * As {@link #evaluate(Map, double, double, byte, double[])}, for an edge that may be walked backwards.
     *
     * @param incline grade in the direction of travel
     * @param inclineAsMapped grade in the direction the edge is mapped: the same, or the opposite if this is the
     *                        backward edge of its pair. Layers whose inclineSpeed has direction "mapped" use this.
     */
    public void evaluate (Map<String, String> tags, double incline, double inclineAsMapped, double lengthMeters,
                          byte curbRamps, double[] out) {
        out[0] = 1;
        out[1] = 0;
        if (tags == null) return;
        ResolvedLayer layer = null;
        for (ResolvedLayer l : layers) {
            if (l.match.matches(tags)) {
                layer = l;
                break;
            }
        }
        if (layer == null) {
            if (profile.otherEdges == PedestrianCostProfile.OtherEdges.IMPASSABLE) out[0] = IMPASSABLE;
            return;
        }
        if (layer.blocked) {
            out[0] = IMPASSABLE;
            return;
        }
        double factor = layer.speedFactor;
        // An edge with no incline is walked at the layer's plain speed, as Walksheds does. It is not treated as flat:
        // level ground is slightly slower than the ideal, gently downhill, grade.
        if (layer.hasIncline && !Double.isNaN(incline)) {
            double i = layer.inclineAsMapped ? inclineAsMapped : incline;
            if (lengthMeters > layer.minLengthForLimits && (i > layer.maxUphill || i < -layer.maxDownhill)) {
                out[0] = IMPASSABLE;
                return;
            }
            double k = (i > layer.ideal) ? layer.kUp : layer.kDown;
            factor *= Math.exp(-k * Math.abs(i - layer.ideal));
        }
        if (layer.requireCurbRamps && curbRamps != OswEdgeAttributes.CURB_RAMPS_YES) {
            out[0] = IMPASSABLE;
            return;
        }
        out[0] = factor / layer.costFactor;
        out[1] = layer.delaySeconds * layer.costFactor;
    }

    /** @return the name of the layer the given tags fall into, or null. For diagnostics and output tagging. */
    public String layerName (Map<String, String> tags) {
        if (tags == null) return null;
        for (ResolvedLayer l : layers) {
            if (l.match.matches(tags)) return l.name;
        }
        return null;
    }

    /** Cost in seconds of traversing an edge, given the evaluate() output. NaN if impassable. */
    public static double seconds (double lengthMeters, double baseSpeed, double speedFactor, double delaySeconds) {
        return lengthMeters / (baseSpeed * speedFactor) + delaySeconds;
    }
}
