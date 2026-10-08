package com.conveyal.r5.osw;

import com.conveyal.r5.analyst.cluster.TransportNetworkConfig;
import com.conveyal.r5.profile.StreetMode;
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
 * Checks that WalkshedsSnapping attaches a point where Unweaver would: on the edge R5 would choose, at the point
 * nearest in longitude and latitude instead of on the ground.
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

    @Test
    public void attachesAtTheNearestPointInLongitudeAndLatitude () {
        EdgeStore.Edge edge = streets.edgeStore.getCursor();
        int checked = 0, apart = 0;
        for (int e = 0; e < streets.edgeStore.nEdges(); e += 2) {
            // A point about 15 m north-east of the middle of each edge.
            Coordinate middle = streets.edgeStore.getCursor(e).getGeometry().getCentroid().getCoordinate();
            double lat = middle.y + 1e-4, lon = middle.x + 1e-4;
            Split ground = Split.find(lat, lon, RADIUS_METERS, streets, StreetMode.WALK, x -> true);
            Split compatible = WalkshedsSnapping.find(lat, lon, RADIUS_METERS, streets, x -> true);
            if (ground == null) {
                assertNull(compatible);
                continue;
            }
            assertNotNull(compatible);
            assertEquals(ground.edge, compatible.edge, "Both attach to the nearest edge.");

            edge.seek(compatible.edge);
            LineString line = edge.getGeometry();
            double nearest = Double.POSITIVE_INFINITY;
            Coordinate expected = null, requested = new Coordinate(lon, lat);
            for (int i = 0; i + 1 < line.getNumPoints(); i++) {
                LineSegment segment = new LineSegment(line.getCoordinateN(i), line.getCoordinateN(i + 1));
                Coordinate closest = segment.closestPoint(requested);
                if (closest.distance(requested) < nearest) {
                    nearest = closest.distance(requested);
                    expected = closest;
                }
            }
            double splitLon = VertexStore.fixedDegreesToFloating(compatible.fixedLon);
            double splitLat = VertexStore.fixedDegreesToFloating(compatible.fixedLat);
            assertEquals(expected.x, splitLon, TOLERANCE_DEGREES, "longitude of the attachment point on edge " + e);
            assertEquals(expected.y, splitLat, TOLERANCE_DEGREES, "latitude of the attachment point on edge " + e);
            assertEquals(edge.getLengthMm(), compatible.distance0_mm + compatible.distance1_mm);
            assertTrue(compatible.distance0_mm >= 0 && compatible.distance1_mm >= 0);
            checked++;
            if (Math.abs(compatible.distance0_mm - ground.distance0_mm) > 1000) apart++;
        }
        assertTrue(checked > 100, "Too few points were attached to check: " + checked);
        // The dataset is at 47 degrees north, where the two ways of finding the nearest point often disagree.
        assertTrue(apart > 10, "The attachment point differed from R5's own by over a meter for only " + apart);
    }
}
