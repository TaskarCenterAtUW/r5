package com.conveyal.r5.osw;

import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.VertexStore;
import org.locationtech.jts.geom.Envelope;

import java.util.function.IntPredicate;

/**
 * Attaches a point to the network for the Walksheds-compatible API: to the nearest edge the traveller can use, at
 * one of two places on it.
 *
 *  - As the TDEI Walksheds service does, which is Unweaver's way, so that routes start and end where Walksheds' do.
 *    Unweaver finds the nearest place using longitude and latitude as they are, as if a degree of each were the same
 *    length. Away from the equator that is not the nearest place on the ground: at 47 degrees north it can be 10 m
 *    further along the edge. The part of the edge before the point is likewise taken as its share of the edge's
 *    length in degrees.
 *  - At the nearest place on the ground.
 *
 * Which edge is nearest is measured on the ground in both, as Unweaver does.
 *
 * {@link Split#find} is not used for either. To find the nearest place on a segment it divides longitudes by the
 * cosine of the latitude where it should multiply by it (GeometryUtils.segmentFraction), so away from the equator its
 * place is off in the opposite direction to Unweaver's and by about as much again, and it can take an edge that is
 * not the nearest.
 *
 * Unweaver's source considers only the four nearest edge records. The deployed service does not behave that way: it
 * attaches to the nearest usable edge even when several unusable ones are nearer, so no such limit is applied here.
 */
public abstract class WalkshedsSnapping {

    private static final double METERS_PER_DEGREE_LAT = 111111.111;

    /**
     * @param usable whether the traveller can use an edge pair, given the index of its forward edge
     * @param asWalksheds whether to attach at the place on the edge Walksheds would, or the nearest on the ground
     * @return where the point attaches, or null if there is no usable edge within the radius
     */
    public static Split find (double lat, double lon, double radiusMeters, StreetLayer streets, IntPredicate usable,
                              boolean asWalksheds) {
        int fixedLat = VertexStore.floatingDegreesToFixed(lat);
        int fixedLon = VertexStore.floatingDegreesToFixed(lon);
        double cosLat = Math.cos(Math.toRadians(lat));
        double radiusFixed = VertexStore.floatingDegreesToFixed(radiusMeters / METERS_PER_DEGREE_LAT);
        Envelope envelope = new Envelope(fixedLon, fixedLon, fixedLat, fixedLat);
        envelope.expandBy(radiusFixed / cosLat, radiusFixed);

        // The nearest usable edge, by its distance on the ground, squared, in fixed-point degrees of latitude.
        EdgeStore.Edge edge = streets.edgeStore.getCursor();
        int[] nearestEdge = {-1};
        double[] nearest = {radiusFixed * radiusFixed};
        streets.findEdgesInEnvelope(envelope).forEach(e -> {
            edge.seek(e);
            if (!linkable(edge)) return true;
            edge.advance();
            if (!linkable(edge)) return true;
            edge.retreat();
            double[] toEdge = {Double.POSITIVE_INFINITY};
            edge.forEachSegment((seg, lat0, lon0, lat1, lon1) -> {
                double frac = fraction(lat0, lon0, lat1, lon1, fixedLat, fixedLon, cosLat);
                double dx = (lon0 + frac * (lon1 - lon0) - fixedLon) * cosLat;
                double dy = lat0 + frac * (lat1 - lat0) - fixedLat;
                toEdge[0] = Math.min(toEdge[0], dx * dx + dy * dy);
            });
            // Ties go to the lower edge index, so that the choice does not depend on the order edges are met in.
            boolean nearer = toEdge[0] < nearest[0] || (toEdge[0] == nearest[0] && e < nearestEdge[0]);
            if (nearer && usable.test(e)) {
                nearest[0] = toEdge[0];
                nearestEdge[0] = e;
            }
            return true;
        });
        if (nearestEdge[0] < 0) return null;
        return onEdge(edge, nearestEdge[0], fixedLat, fixedLon, cosLat, asWalksheds ? 1 : cosLat);
    }

    private static boolean linkable (EdgeStore.Edge edge) {
        return !edge.getFlag(EdgeStore.EdgeFlag.LINK) && edge.getFlag(EdgeStore.EdgeFlag.LINKABLE)
                && edge.allowsStreetMode(StreetMode.WALK);
    }

    /**
     * How far along a segment, from 0 to 1, the place nearest to a point is, when a unit of longitude is xScale times
     * as long as a unit of latitude.
     */
    private static double fraction (double lat0, double lon0, double lat1, double lon1, double lat, double lon,
                                    double xScale) {
        double sx = (lon1 - lon0) * xScale, sy = lat1 - lat0;
        double squaredLength = sx * sx + sy * sy;
        if (squaredLength == 0) return 0;
        double along = ((lon - lon0) * xScale * sx + (lat - lat0) * sy) / squaredLength;
        return Math.max(0, Math.min(1, along));
    }

    /**
     * The place on an edge nearest to the given point, with a unit of longitude taken as xScale times as long as a
     * unit of latitude: the cosine of the latitude for the nearest on the ground, 1 for Unweaver's.
     */
    private static Split onEdge (EdgeStore.Edge edge, int e, int fixedLat, int fixedLon, double cosLat,
                                 double xScale) {
        edge.seek(e);
        Split split = new Split();
        split.edge = e;
        split.vertex0 = edge.getFromVertex();
        split.vertex1 = edge.getToVertex();
        // Lengths along the edge at this scale: the whole edge, and up to the nearest place found so far.
        double[] lengths = new double[2];
        double[] nearest = {Double.POSITIVE_INFINITY};
        edge.forEachSegment((seg, lat0, lon0, lat1, lon1) -> {
            double frac = fraction(lat0, lon0, lat1, lon1, fixedLat, fixedLon, xScale);
            double pointLon = lon0 + frac * (lon1 - lon0), pointLat = lat0 + frac * (lat1 - lat0);
            double dx = (pointLon - fixedLon) * xScale, dy = pointLat - fixedLat;
            double length = Math.hypot((lon1 - lon0) * xScale, lat1 - lat0);
            if (dx * dx + dy * dy < nearest[0]) {
                nearest[0] = dx * dx + dy * dy;
                split.seg = seg;
                split.frac = frac;
                split.fixedLon = (int) Math.round(pointLon);
                split.fixedLat = (int) Math.round(pointLat);
                lengths[1] = lengths[0] + frac * length;
            }
            lengths[0] += length;
        });
        int lengthMm = edge.getLengthMm();
        split.distance0_mm = lengths[0] > 0 ? (int) Math.round(lengthMm * lengths[1] / lengths[0]) : 0;
        split.distance1_mm = lengthMm - split.distance0_mm;
        double dx = (split.fixedLon - fixedLon) * cosLat, dy = split.fixedLat - fixedLat;
        split.distanceToEdge_squaredFixedDegrees = (long) (dx * dx + dy * dy);
        split.distanceToEdge_mm = (int) (VertexStore.fixedDegreesToFloating(Math.hypot(dx, dy))
                * METERS_PER_DEGREE_LAT * 1000);
        return split;
    }
}
