package com.conveyal.r5.osw;

import com.conveyal.r5.analyst.cluster.TransportNetworkConfig;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LineSegment;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that WalkshedsSnapping attaches a point to the edge that is nearest on the ground, at the place on it that is
 * nearest in longitude and latitude (as Unweaver does) or nearest on the ground, whichever is asked for.
 */
public class WalkshedsSnappingTest {

    private static final double RADIUS_METERS = 50;

    /** Fixed-point coordinates are whole units of 1e-7 degrees, about a centimeter. */
    private static final double TOLERANCE_DEGREES = 3e-7;

    private static StreetLayer streets;

    @BeforeAll
    public static void loadNetwork () throws Exception {
        File dir = new File(WalkshedsSnappingTest.class.getResource("walksheds/latah").toURI());
        TransportNetworkConfig config = new TransportNetworkConfig();
        config.pruneIslands = false;
        streets = TransportNetwork.fromOsw(new File(dir, "dataset").getAbsolutePath(), config).streetLayer;
    }

    /** The place on a line nearest to a point, when a unit of longitude is xScale times as long as one of latitude. */
    private static Coordinate nearestOn (LineString line, Coordinate point, double xScale) {
        Coordinate scaled = new Coordinate(point.x * xScale, point.y), best = null;
        for (int i = 0; i + 1 < line.getNumPoints(); i++) {
            Coordinate a = line.getCoordinateN(i), b = line.getCoordinateN(i + 1);
            Coordinate c = new LineSegment(a.x * xScale, a.y, b.x * xScale, b.y).closestPoint(scaled);
            if (best == null || c.distance(scaled) < best.distance(scaled)) best = c;
        }
        return new Coordinate(best.x / xScale, best.y);
    }

    @Test
    public void attachesToTheNearestEdgeAtThePlaceAskedFor () {
        EdgeStore.Edge edge = streets.edgeStore.getCursor();
        int nEdges = streets.edgeStore.nEdges(), apart = 0;
        for (int e = 0; e < nEdges; e += 2) {
            // A point about 15 m north-east of the middle of each edge.
            Coordinate middle = streets.edgeStore.getCursor(e).getGeometry().getCentroid().getCoordinate();
            Coordinate requested = new Coordinate(middle.x + 1e-4, middle.y + 1e-4);
            double cosLat = Math.cos(Math.toRadians(requested.y));
            Split asWalksheds = WalkshedsSnapping.find(requested.y, requested.x, RADIUS_METERS, streets, x -> true, true);
            Split onGround = WalkshedsSnapping.find(requested.y, requested.x, RADIUS_METERS, streets, x -> true, false);
            assertNotNull(asWalksheds);
            assertNotNull(onGround);
            assertEquals(onGround.edge, asWalksheds.edge, "Both attach to the same edge.");

            // No edge is nearer on the ground than the one chosen.
            Coordinate scaled = new Coordinate(requested.x * cosLat, requested.y);
            double chosen = Double.NaN, nearest = Double.POSITIVE_INFINITY;
            for (int other = 0; other < nEdges; other += 2) {
                Coordinate c = nearestOn(streets.edgeStore.getCursor(other).getGeometry(), requested, cosLat);
                double distance = new Coordinate(c.x * cosLat, c.y).distance(scaled);
                nearest = Math.min(nearest, distance);
                if (other == onGround.edge) chosen = distance;
            }
            assertEquals(nearest, chosen, TOLERANCE_DEGREES, "distance to the edge chosen for a point near edge " + e);

            edge.seek(onGround.edge);
            for (Split split : new Split[] {asWalksheds, onGround}) {
                Coordinate expected = nearestOn(edge.getGeometry(), requested, split == asWalksheds ? 1 : cosLat);
                String which = (split == asWalksheds ? "as Walksheds" : "on the ground") + " near edge " + e;
                assertEquals(expected.x, VertexStore.fixedDegreesToFloating(split.fixedLon), TOLERANCE_DEGREES,
                        "longitude of the attachment point " + which);
                assertEquals(expected.y, VertexStore.fixedDegreesToFloating(split.fixedLat), TOLERANCE_DEGREES,
                        "latitude of the attachment point " + which);
                assertEquals(edge.getLengthMm(), split.distance0_mm + split.distance1_mm);
                assertTrue(split.distance0_mm >= 0 && split.distance1_mm >= 0);
            }
            if (Math.abs(asWalksheds.distance0_mm - onGround.distance0_mm) > 1000) apart++;
        }
        // The dataset is at 47 degrees north, where the two ways of finding the nearest place often disagree.
        assertTrue(apart > 10, "The two attachment points were over a meter apart for only " + apart);
    }

    @Test
    public void passesOverEdgesTheTravellerCannotUse () {
        Coordinate middle = streets.edgeStore.getCursor(0).getGeometry().getCentroid().getCoordinate();
        Split any = WalkshedsSnapping.find(middle.y, middle.x, RADIUS_METERS, streets, x -> true, true);
        Split other = WalkshedsSnapping.find(middle.y, middle.x, RADIUS_METERS, streets, x -> x != any.edge, true);
        assertTrue(other == null || other.edge != any.edge);
        assertNull(WalkshedsSnapping.find(middle.y, middle.x, RADIUS_METERS, streets, x -> false, true));
    }
}
