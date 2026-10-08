package com.conveyal.r5.osw;

import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.TraversalTimeCalculator;

/**
 * Applies a pedestrian cost profile to WALK traversals, delegating other modes to the wrapped calculator.
 *
 * This interface gives times in whole seconds, so each edge's cost is rounded up here (minimum 1 second), as for R5's
 * other walk costs. The street router does not add these up: on finding this calculator it takes the unrounded cost
 * from the table and keeps the fraction along the path (see StreetRouter.State.carrySeconds), using the value from
 * here only to learn whether the edge is passable.
 * Impassable edges are signaled by a negative traversal time, which makes EdgeStore.Edge.traverse reject them.
 * Unweaver's cost functions have no turn costs, so walk turn costs are zero.
 */
public class PedestrianCostTimeCalculator implements TraversalTimeCalculator {

    private static final long serialVersionUID = 1L;

    public static final int IMPASSABLE = -1;

    private final TraversalTimeCalculator base;

    private final transient PedestrianCostTable table;

    public PedestrianCostTimeCalculator (TraversalTimeCalculator base, PedestrianCostTable table) {
        this.base = base;
        this.table = table;
    }

    /** @return the calculator this one delegates to for modes other than WALK. */
    public TraversalTimeCalculator getBase () {
        return base;
    }

    public PedestrianCostTable getTable () {
        return table;
    }

    @Override
    public int traversalTimeSeconds (EdgeStore.Edge currentEdge, StreetMode streetMode, ProfileRequest req) {
        if (streetMode != StreetMode.WALK) {
            return base.traversalTimeSeconds(currentEdge, streetMode, req);
        }
        return roundSeconds(table.seconds(currentEdge, req.walkSpeed));
    }

    @Override
    public int turnTimeSeconds (int fromEdge, int toEdge, StreetMode streetMode) {
        if (streetMode == StreetMode.WALK) return 0;
        return base.turnTimeSeconds(fromEdge, toEdge, streetMode);
    }

    /**
     * Convert the cost of part of an edge (from or to a point along it) to integer seconds, or IMPASSABLE for NaN.
     * Unlike whole edges there is no 1 second minimum, as a point may lie at the very end of an edge.
     */
    public static int roundPartialSeconds (double seconds) {
        if (Double.isNaN(seconds)) return IMPASSABLE;
        return (int) Math.ceil(seconds);
    }

    /** Convert a cost in (fractional) seconds to R5's integer seconds, or IMPASSABLE for NaN. */
    public static int roundSeconds (double seconds) {
        if (Double.isNaN(seconds)) return IMPASSABLE;
        return Math.max(1, (int) Math.ceil(seconds));
    }
}
