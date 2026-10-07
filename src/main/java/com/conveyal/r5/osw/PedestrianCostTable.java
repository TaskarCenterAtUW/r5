package com.conveyal.r5.osw;

import com.conveyal.r5.streets.EdgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-edge results of a {@link PedestrianCostSpec} evaluated over a whole EdgeStore, so that the routing inner loop is
 * an array lookup. Tables are cached per EdgeStore and runSpecId (see {@link OswEdgeAttributes#costTable}), so a
 * regional analysis evaluates the profile once rather than once per origin.
 *
 * Edges added after the table was built (e.g. splits made when linking origins in a scenario) are evaluated on demand.
 */
public class PedestrianCostTable {

    private static final Logger LOG = LoggerFactory.getLogger(PedestrianCostTable.class);

    public final PedestrianCostSpec spec;
    private final OswEdgeAttributes attributes;
    private final double[] speedFactor;
    private final double[] delaySeconds;

    PedestrianCostTable (PedestrianCostSpec spec, EdgeStore edgeStore) {
        this.spec = spec;
        this.attributes = edgeStore.oswAttributes;
        int n = edgeStore.nEdges();
        speedFactor = new double[n];
        delaySeconds = new double[n];
        double[] out = new double[2];
        EdgeStore.Edge edge = edgeStore.getCursor();
        int impassable = 0;
        for (int e = 0; e < n; e++) {
            edge.seek(e);
            evaluate(edge, out);
            speedFactor[e] = out[0];
            delaySeconds[e] = out[1];
            if (Double.isNaN(out[0])) impassable++;
        }
        LOG.info("Evaluated pedestrian cost profile {} (profileId {}, runSpecId {}) over {} edges, {} impassable.",
                spec.profile.name, spec.profile.profileId, spec.runSpecId, n, impassable);
    }

    private void evaluate (EdgeStore.Edge edge, double[] out) {
        int e = edge.getEdgeIndex();
        double oswLength = attributes.lengthMeters(e);
        double lengthForLimits = Double.isNaN(oswLength) ? edge.getLengthM() : oswLength;
        spec.evaluate(attributes.tags(e), attributes.incline(e), lengthForLimits, attributes.curbRamps(e), out);
    }

    /**
     * @return the cost in seconds of traversing the edge at the cursor walking at baseSpeed, or NaN if the edge is
     *         impassable. Uses the edge's current length, so partial (split) edges are costed proportionally.
     */
    public double seconds (EdgeStore.Edge edge, double baseSpeed) {
        return seconds(edge, baseSpeed, edge.getLengthM());
    }

    /**
     * As {@link #seconds(EdgeStore.Edge, double)} but for traversing only lengthMeters of the edge, e.g. from an
     * origin point partway along it. Any fixed delay is applied in full, as Unweaver does when it splits an edge.
     */
    public double seconds (EdgeStore.Edge edge, double baseSpeed, double lengthMeters) {
        int e = edge.getEdgeIndex();
        double factor, delay;
        if (e < speedFactor.length) {
            factor = speedFactor[e];
            delay = delaySeconds[e];
        } else {
            double[] out = new double[2];
            evaluate(edge, out);
            factor = out[0];
            delay = out[1];
        }
        if (Double.isNaN(factor)) return Double.NaN;
        return PedestrianCostSpec.seconds(lengthMeters, baseSpeed, factor, delay);
    }

    /**
     * A point this close to the end of an edge, in meters, is treated as being at that end. Coordinates are stored to
     * about a centimeter and snapping is done in those units, so a point given at a node's position can land a few
     * centimeters along one of the node's edges.
     */
    public static final double AT_END_METERS = 0.1;

    /**
     * The cost of getting between a point on an edge and one of its ends, lengthMeters away. As
     * {@link #seconds(EdgeStore.Edge, double, double)}, except that a point at the end itself is already there: that
     * costs nothing, with no delay, even if the edge is impassable. Unweaver likewise starts from the node when a
     * point is at one. A point at a node is snapped to whichever of the node's edges is found first, so this also
     * keeps the result from depending on that.
     */
    public double partialSeconds (EdgeStore.Edge edge, double baseSpeed, double lengthMeters) {
        return lengthMeters < AT_END_METERS ? 0 : seconds(edge, baseSpeed, lengthMeters);
    }

    /** @return the speed factor for the given edge (NaN if impassable). For diagnostics. */
    public double speedFactor (int edgeIndex) {
        return edgeIndex < speedFactor.length ? speedFactor[edgeIndex] : Double.NaN;
    }
}
