package com.conveyal.r5.osw;

import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.VertexStore;

import java.util.function.IntPredicate;

/**
 * Attaches a point to the network the way the TDEI Walksheds service does, which is Unweaver's way, so that routes
 * start and end where Walksheds' do.
 *
 * Both choose the nearest edge the traveller can use, measured on the ground. They differ in where on that edge the
 * point attaches. Unweaver finds the nearest point using longitude and latitude as they are, as if a degree of each
 * were the same length. Away from the equator that is not the nearest point on the ground, which is what
 * {@link Split#find} gives: at 47 degrees north it can be 10 m further along the edge. The part of the edge before
 * the point is likewise taken as its share of the edge's length in degrees.
 *
 * Unweaver's source considers only the four nearest edge records. The deployed service does not behave that way: it
 * attaches to the nearest usable edge even when several unusable ones are nearer, so no such limit is applied here.
 */
public abstract class WalkshedsSnapping {

    private static final double METERS_PER_DEGREE_LAT = 111111.111;

    /**
     * @param usable whether the traveller can use an edge pair, given the index of its forward edge
     * @return where the point attaches, or null if there is no usable edge within the radius
     */
    public static Split find (double lat, double lon, double radiusMeters, StreetLayer streets, IntPredicate usable) {
        Split nearest = Split.find(lat, lon, radiusMeters, streets, StreetMode.WALK, usable);
        if (nearest == null) return null;
        return onEdge(streets.edgeStore.getCursor(), nearest.edge, VertexStore.floatingDegreesToFixed(lat),
                VertexStore.floatingDegreesToFixed(lon), Math.cos(Math.toRadians(lat)));
    }

    /** The point on an edge nearest to the given one, taking longitude and latitude as plane coordinates. */
    private static Split onEdge (EdgeStore.Edge edge, int e, int fixedLat, int fixedLon, double cosLat) {
        edge.seek(e);
        Split split = new Split();
        split.edge = e;
        split.vertex0 = edge.getFromVertex();
        split.vertex1 = edge.getToVertex();
        // Lengths along the edge in degrees: the whole edge, and up to the nearest point found so far.
        double[] degrees = new double[2];
        double[] nearest = {Double.POSITIVE_INFINITY};
        edge.forEachSegment((seg, lat0, lon0, lat1, lon1) -> {
            double frac = GeometryUtils.segmentFraction(lon0, lat0, lon1, lat1, fixedLon, fixedLat, 1);
            double pointLon = lon0 + frac * (lon1 - lon0), pointLat = lat0 + frac * (lat1 - lat0);
            double dx = pointLon - fixedLon, dy = pointLat - fixedLat;
            double length = Math.hypot(lon1 - lon0, lat1 - lat0);
            if (dx * dx + dy * dy < nearest[0]) {
                nearest[0] = dx * dx + dy * dy;
                split.seg = seg;
                split.frac = frac;
                split.fixedLon = (int) pointLon;
                split.fixedLat = (int) pointLat;
                degrees[1] = degrees[0] + frac * length;
            }
            degrees[0] += length;
        });
        int lengthMm = edge.getLengthMm();
        split.distance0_mm = degrees[0] > 0 ? (int) Math.round(lengthMm * degrees[1] / degrees[0]) : 0;
        split.distance1_mm = lengthMm - split.distance0_mm;
        double dx = (split.fixedLon - fixedLon) * cosLat, dy = split.fixedLat - fixedLat;
        split.distanceToEdge_squaredFixedDegrees = (long) (dx * dx + dy * dy);
        split.distanceToEdge_mm = (int) (VertexStore.fixedDegreesToFloating(Math.hypot(dx, dy))
                * METERS_PER_DEGREE_LAT * 1000);
        return split;
    }
}
