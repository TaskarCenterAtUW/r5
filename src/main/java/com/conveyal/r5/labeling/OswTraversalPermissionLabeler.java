package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.analyst.cluster.TransportNetworkConfig;
import com.conveyal.r5.streets.EdgeStore;

import java.util.EnumSet;

/**
 * Permission labeler for networks built from OpenSidewalks (OSW) data. OSW networks are pedestrian networks, so every
 * edge is walkable (and wheelchair-traversable as far as R5's flags are concerned) in both directions, and no edge
 * allows bikes or cars. Which edges a particular user can actually traverse, and at what cost, is decided per request
 * by a pedestrian cost profile (see com.conveyal.r5.osw.PedestrianCostProfile), not at network build time.
 */
public class OswTraversalPermissionLabeler extends TraversalPermissionLabeler {

    public static final String NAME = "osw";

    public OswTraversalPermissionLabeler (TransportNetworkConfig config) {
        super(config);
    }

    @Override
    public RoadPermission getPermissions (Way way) {
        return new RoadPermission(
                EnumSet.of(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, EdgeStore.EdgeFlag.ALLOWS_WHEELCHAIR),
                EnumSet.of(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, EdgeStore.EdgeFlag.ALLOWS_WHEELCHAIR)
        );
    }
}
