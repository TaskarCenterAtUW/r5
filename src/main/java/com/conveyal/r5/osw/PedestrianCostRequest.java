package com.conveyal.r5.osw;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.Serializable;
import java.util.Map;

/**
 * The part of a routing request that selects a pedestrian cost profile and supplies the user's parameter values.
 * Applies to WALK on networks built from OpenSidewalks data.
 *
 * JSON form, as a field "pedestrianCost" of the ProfileRequest / analysis task:
 * <pre>
 * "pedestrianCost": {
 *   "profile": { ...full profile definition, see PedestrianCostProfile... },
 *   "parameters": { "uphill": 0.083, "downhill": 0.1, "avoidCurbs": true }
 * }
 * </pre>
 * The profile is sent inline (it is small), so the profile used is always exactly the one the client holds, and its
 * profileId identifies it. Base walking speed is the request's walkSpeed.
 */
public class PedestrianCostRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    public JsonNode profile;

    public Map<String, Object> parameters;

    @JsonIgnore
    private transient volatile PedestrianCostSpec spec;

    public PedestrianCostRequest () { }

    public PedestrianCostRequest (JsonNode profile, Map<String, Object> parameters) {
        this.profile = profile;
        this.parameters = parameters;
    }

    /** @return the validated profile combined with parameter values. Resolved once per request object. */
    @JsonIgnore
    public PedestrianCostSpec spec () {
        PedestrianCostSpec s = spec;
        if (s == null) {
            if (profile == null) throw new IllegalArgumentException("pedestrianCost.profile is required.");
            s = new PedestrianCostProfile(profile).resolve(parameters);
            spec = s;
        }
        return s;
    }
}
