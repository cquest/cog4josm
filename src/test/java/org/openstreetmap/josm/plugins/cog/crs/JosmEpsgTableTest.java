package org.openstreetmap.josm.plugins.cog.crs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Behaviour of the host projection table fallback (JOSM's
 * data/projection/custom-epsg). The resource comes from josm-tested.jar, on
 * the test classpath since build.gradle's testImplementation files() entry -
 * if this test cannot see the table, Crs.forEpsg would fail as well.
 */
class JosmEpsgTableTest {

    @Test
    void resourceVisible() {
        assertTrue(JosmEpsgTable.size() > 5000,
                "host projection table must be on the classpath");
    }

    @Test
    void unknownCodeReason() {
        assertTrue(JosmEpsgTable.reason(999999).contains("absent"));
    }

    /** Models the host table lists but the plugin does not compute: explicit reasons. */
    @Test
    void rejectedModelsFromRealEntries() {
        assertTrue(JosmEpsgTable.reason(2172).contains("sterea"));
        assertTrue(JosmEpsgTable.reason(53004).contains("Mercator is not taken"));
        assertTrue(JosmEpsgTable.reason(23700).contains("somerc"));
        // aea/laea/stere were routed to the proj4j fallback until phase 2.3;
        // since its removal (2.4) they fall into the generic model refusal
        assertTrue(JosmEpsgTable.reason(102018).contains("model +proj=stere"));
        assertTrue(JosmEpsgTable.reason(3035).contains("model +proj=laea"));
        assertTrue(JosmEpsgTable.reason(102001).contains("model +proj=aea"));
        assertNull(JosmEpsgTable.create(102018));
        assertNull(JosmEpsgTable.create(2172));
        assertNull(JosmEpsgTable.create(53004));
        assertNull(JosmEpsgTable.create(3035));
        assertNull(JosmEpsgTable.create(102001));
    }

    private static void assertRejected(String reasonFragment, String def) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JosmEpsgTable.build(1, def));
        assertTrue(e.getMessage().contains(reasonFragment),
                "message [" + e.getMessage() + "] should mention [" + reasonFragment + "]");
    }

    @Test
    void unsupportedTokensRejected() {
        assertRejected("axis order", "+proj=tmerc +lat_0=0 +lon_0=3 +ellps=WGS84 +axis=wsu");
        assertRejected("vertical CRS", "+proj=utm +zone=31 +datum=WGS84 +vunits=m");
        assertRejected("linear unit", "+proj=tmerc +lat_0=0 +lon_0=3 +ellps=WGS84 +units=ind-yd");
        assertRejected("cannot be combined",
                "+proj=tmerc +lat_0=0 +lon_0=3 +ellps=WGS84 +a=6378137");
        assertRejected("cannot be combined",
                "+proj=tmerc +lat_0=0 +lon_0=3 +ellps=WGS84 +units=ft +to_meter=0.3048");
        assertRejected("not supported for +proj=tmerc",
                "+proj=tmerc +lat_0=0 +lon_0=3 +lonc=5 +ellps=WGS84");
        assertRejected("duplicated token",
                "+proj=tmerc +lat_0=0 +lat_0=1 +lon_0=3 +ellps=WGS84");
        assertRejected("principal meridian",
                "+proj=tmerc +lat_0=0 +lon_0=3 +ellps=WGS84 +pm=paris");
        assertRejected("unknown ellipsoid name",
                "+proj=tmerc +lat_0=0 +lon_0=3 +ellps=notreal");
        assertRejected("unknown datum name",
                "+proj=tmerc +lat_0=0 +lon_0=3 +datum=notreal");
        assertRejected("invalid angle", "+proj=longlat +ellps=WGS84 +pm=ferro");
    }

    @Test
    void modelShapeGuards() {
        assertRejected("missing parameter +lat_1",
                "+proj=lcc +lat_0=46 +lon_0=3 +ellps=GRS80");
        assertRejected("null standard parallels",
                "+proj=lcc +lat_1=0 +lat_0=46 +lon_0=3 +ellps=GRS80");
        assertRejected("UTM zone out of range", "+proj=utm +zone=61 +datum=WGS84");
        // models of the removed proj4j fallback: refused at model level,
        // whatever their parameter shape
        assertRejected("model +proj=stere",
                "+proj=stere +lat_0=90 +lat_ts=70 +lon_0=0 +k=0.999 +datum=WGS84");
        assertRejected("model +proj=laea", "+proj=laea +lat_0=52 +lon_0=10 +ellps=sphere");
        assertRejected("model +proj=aea",
                "+proj=aea +lat_1=30 +lat_2=60 +lat_0=40 +lon_0=-96 +ellps=GRS80");
    }

    /** DMS spellings (52d9'22.178", 5d23'15.5", 0dE, 96dW) parse as decimal degrees. */
    @Test
    void dmsAnglesEqualDecimal() {
        Projection dms = JosmEpsgTable.build(1, "+proj=lcc +lat_1=52d9'22.178\" +lat_2=52d51'30\""
                + " +lat_0=52d9'22.178\" +lon_0=5d23'15.5\" +x_0=155000 +y_0=463000 +ellps=bessel");
        Projection dec = JosmEpsgTable.build(2, "+proj=lcc +lat_1=52.15616055555556"
                + " +lat_2=52.85833333333333 +lat_0=52.15616055555556 +lon_0=5.387638888888889"
                + " +x_0=155000 +y_0=463000 +ellps=bessel");
        double[] a = dms.project(52.4, 5.1);
        double[] b = dec.project(52.4, 5.1);
        assertEquals(b[0], a[0], 1e-6);
        assertEquals(b[1], a[1], 1e-6);
        // hemisphere letters carry the sign
        Projection west = JosmEpsgTable.build(3,
                "+proj=longlat +ellps=WGS84 +pm=96dW");
        double[] xy = west.project(40.0, -100.0);
        assertEquals(-100.0 + 96.0, xy[0], 1e-12);
        assertEquals(40.0, xy[1], 1e-12);
    }

    /** Geographic entries honour +pm like PROJ: x = longitude - meridian. */
    @Test
    void geographicPrimeMeridian() {
        Projection p = JosmEpsgTable.build(4,
                "+proj=longlat +ellps=WGS84 +pm=74.08096444444444");
        double[] xy = p.project(4.6, -80.0);
        assertEquals(-80.0 - 74.08096444444444, xy[0], 1e-9);
        assertEquals(4.6, xy[1], 0.0);
    }

    /**
     * Linear units: PROJ adds +x_0/+y_0 in metres then expresses the whole
     * output in the declared unit (gdaltransform behaviour, mirrored by the
     * Scaled wrapper) - the us-ft anchors of ProjectionTest cover the numbers.
     */
    @Test
    void linearUnitScaling() {
        Projection p = JosmEpsgTable.build(5,
                "+proj=tmerc +lat_0=0 +lon_0=3 +k=1 +x_0=304800 +y_0=0 +ellps=WGS84 +units=ft");
        double[] xy = p.project(0.0, 3.0);
        assertEquals(304800.0 / 0.3048, xy[0], 1e-6);
        assertEquals(0.0, xy[1], 1e-9);
    }
}
