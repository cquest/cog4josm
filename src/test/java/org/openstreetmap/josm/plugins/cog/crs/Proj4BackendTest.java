package org.openstreetmap.josm.plugins.cog.crs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.cog.reader.CustomCrs;
import org.openstreetmap.josm.plugins.cog.reader.CustomCrs.Key;

/**
 * Phase 2.2: proj4j fallback for projection models absent from the in-house
 * crs/ classes. Every anchor was produced by gdaltransform (PROJ) on GeoKeys
 * exactly as GDAL 3.x writes them (campaign of /tmp/opencode/mth fixtures);
 * the tolerances (1 mm, 1e-7 degrees) encode the accuracy policy: a model is
 * routed to the fallback only while proj4j agrees with PROJ at this level.
 *
 * Anchor rows are {lon, lat, x, y}.
 */
class Proj4BackendTest {

    private static final double TOL_M = 1e-3;
    private static final double TOL_DEG = 1e-7;

    /** Albers GRS80, sp 42/44, lat0 46, lon0 3, FE 1000000, FN 6000000 (custom_albers_cog.tif). */
    private static final double[][] ALBERS = {
        {3.0, 46.5, 1000000.000000, 6055492.678322},
        {2.5, 48.0, 962538.815847, 6221897.413543},
        {2.25, 48.8, 944598.935787, 6310573.292141},
        {2.45, 48.92, 959459.165173, 6323728.790223},
        {-5.0, 43.0, 348756.215346, 5697765.804575},
    };

    /** Lambert azimuthal equal area GRS80, natural origin (2.55, 46.5), FE/FN 4321000/2221000. */
    private static final double[][] LAEA = {
        {2.35, 48.85, 4306318.005054, 2482276.767836},
        {3.1, 49.1, 4361175.828212, 2510193.477332},
        {-1.0, 45.0, 4041191.354772, 2060513.332061},
        {3.0, 46.5, 4355542.938597, 2221098.084028},
        {2.5, 48.0, 4317268.303755, 2387757.550968},
    };

    /** Cylindrical equal area GRS80, lat_ts 45, lon_0 3. */
    private static final double[][] CEA = {
        {2.35, 48.85, -51250.442812, 6752333.657768},
        {3.1, 49.1, 7884.683509, 6778147.774913},
        {-1.0, 45.0, -315387.340379, 6338983.732421},
        {3.0, 46.5, 0.000000, 6503509.209532},
        {2.5, 48.0, -39423.417547, 6663613.491037},
    };

    /** Oblique Stereographic GRS80, natural origin (3, 46), k_0 0.9999, FE/FN 500000. */
    private static final double[][] STEREO = {
        {2.35, 48.85, 452263.281074, 817119.399686},
        {3.1, 49.1, 507308.460453, 844748.378099},
        {-1.0, 45.0, 184704.795834, 396697.488350},
        {3.0, 46.5, 500000.000000, 555573.708003},
        {2.5, 48.0, 462675.461567, 722473.772897},
    };

    /** Polar Stereographic WGS84, lat_ts 71 (north), lon_0 8 (GDAL GeoKeys 3075=15). */
    private static final double[][] POLAR = {
        {2.35, 48.85, -459367.307514, -4643263.721452},
        {3.1, 49.1, -395916.559653, -4618166.838674},
        {-1.0, 45.0, -805180.267973, -5083708.136501},
        {3.0, 46.5, -432130.111461, -4939269.775592},
        {2.5, 48.0, -457290.108908, -4749139.361826},
    };

    /** Swiss oblique stereographic (somerc), Bessel 1841, center (7.4395833, 46.9524056). */
    private static final double[][] SOMERC = {
        {2.35, 48.85, 226564.696148, 423133.826839},
        {3.1, 49.1, 283074.126589, 447599.320234},
        {-1.0, 45.0, -64415.443723, 18751.549736},
        {3.0, 46.5, 259420.070193, 159356.477484},
        {2.5, 48.0, 231604.764895, 328072.684716},
    };

    private static CustomCrs custom(Key[] keys, double... dbl) {
        return new CustomCrs(keys, dbl, null);
    }

    /** Header keys shared by all projected fixtures below (model type, units...). */
    private static Key[] header(int method) {
        return new Key[]{
            new Key(1024, 0, 1, 1),
            new Key(2048, 0, 1, 32767),
            new Key(2050, 0, 1, 32767),
            new Key(2054, 0, 1, 9102),
            new Key(3072, 0, 1, 32767),
            new Key(3074, 0, 1, 32767),
            new Key(3075, 0, 1, method),
            new Key(3076, 0, 1, 9001),
        };
    }

    private static Key[] concat(Key[] a, Key[] b) {
        Key[] all = new Key[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    /** GRS80 through the doubles (as GDAL writes: 2057/2059 with 2056=7019). */
    private static Key[] grs80Keys(int iA, int iRf) {
        return new Key[]{
            new Key(2056, 0, 1, 7019),
            new Key(2057, 34736, 1, iA),
            new Key(2059, 34736, 1, iRf),
        };
    }

    private static void assertMatches(Projection p, double[][] anchors) {
        for (double[] a : anchors) {
            double[] xy = p.project(a[1], a[0]);
            assertEquals(a[2], xy[0], TOL_M, "x at lon=" + a[0] + " lat=" + a[1]);
            assertEquals(a[3], xy[1], TOL_M, "y at lon=" + a[0] + " lat=" + a[1]);
        }
    }

    private static void assertInverse(Projection p, double[][] anchors) {
        for (double[] a : anchors) {
            double[] ll = p.unproject(a[2], a[3]);
            assertEquals(a[1], ll[0], TOL_DEG, "lat at (" + a[2] + ", " + a[3] + ")");
            assertEquals(a[0], ll[1], TOL_DEG, "lon at (" + a[2] + ", " + a[3] + ")");
        }
    }

    // ------------------------------------------------------- verified models

    @Test
    void supportsCoversTheVerifiedSubsetOnly() {
        assertTrue(Proj4Backend.supports(10)); // laea
        assertTrue(Proj4Backend.supports(11)); // albers
        assertTrue(Proj4Backend.supports(14)); // stereographic (GDAL 3075 table)
        assertTrue(Proj4Backend.supports(15)); // polar stereographic
        assertTrue(Proj4Backend.supports(28)); // cylindrical equal area
        assertTrue(Proj4Backend.supports(9815)); // Hotine (90/90 form only, checked later)
        // methods where proj4j drifts from PROJ, or nothing to map: stay out
        assertTrue(!Proj4Backend.supports(1) && !Proj4Backend.supports(6)
                && !Proj4Backend.supports(7) && !Proj4Backend.supports(8)
                && !Proj4Backend.supports(9) && !Proj4Backend.supports(13)
                && !Proj4Backend.supports(21) && !Proj4Backend.supports(22));
    }

    @Test
    void albersMatchesGdal() {
        // GeoKeys of custom_albers_cog.tif as GDAL wrote them
        Key[] keys = concat(header(11), concat(grs80Keys(6, 7), new Key[]{
            new Key(3078, 34736, 1, 0),
            new Key(3079, 34736, 1, 1),
            new Key(3080, 34736, 1, 2),
            new Key(3081, 34736, 1, 3),
            new Key(3082, 34736, 1, 4),
            new Key(3083, 34736, 1, 5),
        }));
        double[] d = {42.0, 44.0, 3.0, 46.0, 1000000.0, 6000000.0, 6378137.0, 298.257222101004};
        Projection p = Crs.fromCustom(custom(keys, d));
        assertEquals(0, p.epsg());
        assertMatches(p, ALBERS);
        assertInverse(p, ALBERS);
    }

    @Test
    void laeaMatchesGdal() {
        Key[] keys = concat(header(10), concat(grs80Keys(4, 5), new Key[]{
            new Key(3082, 34736, 1, 2),
            new Key(3083, 34736, 1, 3),
            new Key(3088, 34736, 1, 0),
            new Key(3089, 34736, 1, 1),
        }));
        double[] d = {2.55, 46.5, 4321000.0, 2221000.0, 6378137.0, 298.257222101};
        Projection p = Crs.fromCustom(custom(keys, d));
        assertMatches(p, LAEA);
        assertInverse(p, LAEA);
    }

    @Test
    void ceaMatchesGdal() {
        Key[] keys = concat(header(28), concat(grs80Keys(4, 5), new Key[]{
            new Key(3078, 34736, 1, 0),
            new Key(3080, 34736, 1, 1),
            new Key(3082, 34736, 1, 2),
            new Key(3083, 34736, 1, 3),
        }));
        double[] d = {45.0, 3.0, 0.0, 0.0, 6378137.0, 298.257222101};
        Projection p = Crs.fromCustom(custom(keys, d));
        assertMatches(p, CEA);
        assertInverse(p, CEA);
    }

    @Test
    void stereographicKFormMatchesGdal() {
        // 3075=14 without standard parallels: the GDAL oblique stereographic,
        // NOT the libgeotiff Lambert 1SP (see the regression test below)
        Key[] keys = concat(header(14), concat(grs80Keys(5, 6), new Key[]{
            new Key(3082, 34736, 1, 2),
            new Key(3083, 34736, 1, 3),
            new Key(3088, 34736, 1, 0),
            new Key(3089, 34736, 1, 1),
            new Key(3092, 34736, 1, 4),
        }));
        double[] d = {3.0, 46.0, 500000.0, 500000.0, 0.9999, 6378137.0, 298.257222101};
        Projection p = Crs.fromCustom(custom(keys, d));
        assertMatches(p, STEREO);
        assertInverse(p, STEREO);
    }

    @Test
    void polarStereographicMatchesGdal() {
        Key[] keys = concat(header(15), new Key[]{
            new Key(2056, 0, 1, 7030),
            new Key(3081, 34736, 1, 0),
            new Key(3082, 34736, 1, 1),
            new Key(3083, 34736, 1, 2),
            new Key(3092, 34736, 1, 3),
            new Key(3095, 34736, 1, 4),
        });
        double[] d = {71.0, 0.0, 0.0, 1.0, 8.0};
        Projection p = Crs.fromCustom(custom(keys, d));
        assertMatches(p, POLAR);
        assertInverse(p, POLAR);
    }

    @Test
    void southPoleSignFollowsStandardParallel() {
        // same keys, lat_ts = -71: the south pole must come out at -90,
        // verified against the anchor of the north case mirrored (y sign)
        Key[] keys = concat(header(15), new Key[]{
            new Key(2056, 0, 1, 7030),
            new Key(3081, 34736, 1, 0),
            new Key(3095, 34736, 1, 1),
        });
        double[] d = {-71.0, 8.0};
        Projection p = Crs.fromCustom(custom(keys, d));
        // round trip south of the equator, lon_0 reference
        double[] xy = p.project(-46.5, 8.0);
        double[] back = p.unproject(xy[0], xy[1]);
        assertEquals(-46.5, back[0], TOL_DEG);
        assertEquals(8.0, back[1], TOL_DEG);
        // the south pole itself maps to the false origin (0, 0)
        double[] pole = p.project(-89.999999, 0.0);
        assertTrue(Math.hypot(pole[0], pole[1]) < 10.0, "south pole near (0,0)");
    }

    @Test
    void swissObliqueStereographicMatchesGdal() {
        // 3075=9815 with azimuth = rectified-grid angle = 90 (somerc, Bessel)
        Key[] keys = concat(header(9815), new Key[]{
            new Key(2056, 0, 1, 32767),
            new Key(2057, 34736, 1, 6),
            new Key(2059, 34736, 1, 7),
            new Key(3082, 34736, 1, 4),
            new Key(3083, 34736, 1, 5),
            new Key(3088, 34736, 1, 0),
            new Key(3089, 34736, 1, 1),
            new Key(3093, 34736, 1, 2),
            new Key(3094, 34736, 1, 3),
            new Key(3096, 34736, 1, 3), // 90
        });
        double[] d = {7.439583333333333, 46.9524055555556, 1.0, 90.0, 600000.0, 200000.0,
            6377397.155, 299.1528128};
        Projection p = Crs.fromCustom(custom(keys, d));
        assertMatches(p, SOMERC);
        assertInverse(p, SOMERC);
    }

    // -------------------------------------------------------- rejections

    private static IllegalArgumentException rejected(Key[] keys, double[] d) {
        return assertThrows(IllegalArgumentException.class, () -> Crs.fromCustom(custom(keys, d)));
    }

    @Test
    void equidistantConicStaysRejected() {
        // proj4j eqdc drifts from PROJ by > 5000 km on the anchor set
        Key[] keys = concat(header(13), concat(grs80Keys(6, 7), new Key[]{
            new Key(3078, 34736, 1, 0),
            new Key(3079, 34736, 1, 1),
            new Key(3080, 34736, 1, 2),
            new Key(3081, 34736, 1, 3),
            new Key(3082, 34736, 1, 4),
            new Key(3083, 34736, 1, 5),
        }));
        double[] d = {42.0, 44.0, 3.0, 46.0, 1000000.0, 6000000.0, 6378137.0, 298.257222101};
        assertTrue(rejected(keys, d).getMessage().contains("unsupported"));
    }

    @Test
    void mercatorWithStandardParallelStaysRejected() {
        Key[] keys = concat(header(7), concat(grs80Keys(3, 4), new Key[]{
            new Key(3078, 34736, 1, 0),
            new Key(3080, 34736, 1, 1),
            new Key(3081, 34736, 1, 2),
        }));
        double[] d = {45.0, 3.0, 0.0, 6378137.0, 298.257222101};
        assertTrue(rejected(keys, d).getMessage().contains("unsupported"));
    }

    @Test
    void polyconicAndOrthographicStayRejected() {
        double[] d = {3.0, 46.0, 500000.0, 500000.0, 1.0, 6378137.0, 298.257222101};
        Key[] poly = concat(header(22), concat(grs80Keys(5, 6), new Key[]{
            new Key(3080, 34736, 1, 0),
            new Key(3081, 34736, 1, 1),
            new Key(3082, 34736, 1, 2),
            new Key(3083, 34736, 1, 3),
            new Key(3092, 34736, 1, 4),
        }));
        assertTrue(rejected(poly, d).getMessage().contains("unsupported"));
        Key[] ortho = concat(header(21), concat(grs80Keys(5, 6), new Key[]{
            new Key(3088, 34736, 1, 0),
            new Key(3089, 34736, 1, 1),
        }));
        assertTrue(rejected(ortho, d).getMessage().contains("unsupported"));
    }

    @Test
    void generalHotineObliqueMercatorRejected() {
        // 3075=9815 with azimuth 30: proj4j omerc drifts from PROJ's Hotine B
        Key[] keys = concat(header(9815), concat(grs80Keys(6, 7), new Key[]{
            new Key(3088, 34736, 1, 0),
            new Key(3089, 34736, 1, 1),
            new Key(3093, 34736, 1, 2),
            new Key(3094, 34736, 1, 3),
            new Key(3096, 34736, 1, 4),
            new Key(3082, 34736, 1, 5),
            new Key(3083, 34736, 1, 5),
        }));
        double[] d = {0.0, 46.0, 0.9999, 30.0, 30.0, 0.0, 6378137.0, 298.257222101};
        assertTrue(rejected(keys, d).getMessage().contains("azimuth"));
    }

    @Test
    void polarWithoutPoleSignRejected() {
        Key[] keys = concat(header(15), new Key[]{
            new Key(2056, 0, 1, 7030),
            new Key(3081, 34736, 1, 0),
        });
        IllegalArgumentException e = rejected(keys, new double[]{0.0});
        assertTrue(e.getMessage().contains("pole"));
    }

    @Test
    void polarWithScaleFactorRejected() {
        Key[] keys = concat(header(15), new Key[]{
            new Key(2056, 0, 1, 7030),
            new Key(3081, 34736, 1, 0),
            new Key(3092, 34736, 1, 1),
        });
        assertTrue(rejected(keys, new double[]{71.0, 0.9999}).getMessage().contains("3092"));
    }

    @Test
    void albersWithoutFirstStandardParallelRejected() {
        Key[] keys = concat(header(11), concat(grs80Keys(3, 4), new Key[]{
            new Key(3080, 34736, 1, 0),
            new Key(3081, 34736, 1, 1),
            new Key(3082, 34736, 1, 2),
        }));
        double[] d = {3.0, 46.0, 1000000.0, 6378137.0, 298.257222101};
        assertTrue(rejected(keys, d).getMessage().contains("3078"));
    }

    // --------------------------------------------------------- regressions

    @Test
    void lambertOneSpStillUsesInHouseClass() {
        // 3075=14 WITH standard parallels: libgeotiff Lambert 1SP, not the
        // stereographic (the collision pitfall of the dual code tables)
        Key[] keys = concat(header(14), concat(grs80Keys(3, 4), new Key[]{
            new Key(3078, 34736, 1, 0),
            new Key(3081, 34736, 1, 1),
            new Key(3080, 34736, 1, 2),
        }));
        double[] d = {46.0, 46.0, 3.0, 6378137.0, 298.257222101};
        Projection p = Crs.fromCustom(custom(keys, d));
        assertInstanceOf(LambertConformalConic2SP.class, p);
    }

    @Test
    void sphereEllipsoidRejected() {
        // proj4j forward is exact on a sphere, its Albers inverse is not
        // (measured against PROJ): the fallback must reject 1/f = 0
        Key[] keys = concat(header(11), new Key[]{
            new Key(2057, 34736, 1, 6),
            new Key(2059, 34736, 1, 7),
            new Key(3078, 34736, 1, 0),
            new Key(3079, 34736, 1, 1),
            new Key(3080, 34736, 1, 2),
            new Key(3081, 34736, 1, 3),
            new Key(3082, 34736, 1, 4),
            new Key(3083, 34736, 1, 5),
        });
        double[] d = {42.0, 44.0, 3.0, 46.0, 0.0, 0.0, 6371000.0, 0.0};
        assertTrue(rejected(keys, d).getMessage().contains("sphere"));
    }

    @Test
    void parametersNeverUseScientificNotation() {
        // 1.23E7 must reach proj4j as "12300000": an "E+" token would be
        // mangled by its parameter parser (NumberFormatException -> rejection)
        Key[] keys = concat(header(11), concat(grs80Keys(6, 7), new Key[]{
            new Key(3078, 34736, 1, 0),
            new Key(3079, 34736, 1, 1),
            new Key(3080, 34736, 1, 2),
            new Key(3081, 34736, 1, 3),
            new Key(3082, 34736, 1, 4),
            new Key(3083, 34736, 1, 5),
        }));
        double[] d = {42.0, 44.0, 3.0, 46.0, 1.23E7, 6.0E6, 6378137.0, 298.257222101};
        Projection p = Crs.fromCustom(custom(keys, d));
        double[] xy = p.project(46.0, 3.0); // natural origin -> exactly (x_0, y_0)
        assertEquals(1.23E7, xy[0], 1e-6);
        assertEquals(6.0E6, xy[1], 1e-6);
    }

    @Test
    void transformOutsideDomainYieldsNaN() {
        // laea seen from the antipodes: never an exception, NaN is the signal
        Key[] keys = concat(header(10), concat(grs80Keys(2, 3), new Key[]{
            new Key(3088, 34736, 1, 0),
            new Key(3089, 34736, 1, 1),
        }));
        double[] d = {-170.0, 80.0, 6378137.0, 298.257222101};
        Projection p = Crs.fromCustom(custom(keys, d));
        double[] xy = p.project(-80.0, 10.0);
        assertTrue(Double.isNaN(xy[0]) && Double.isNaN(xy[1]));
    }

    @Test
    void albersThroughResolve() {
        // a COG declaring 32767 end-to-end through the registry fallback
        Key[] keys = concat(header(11), concat(grs80Keys(6, 7), new Key[]{
            new Key(3078, 34736, 1, 0),
            new Key(3079, 34736, 1, 1),
            new Key(3080, 34736, 1, 2),
            new Key(3081, 34736, 1, 3),
            new Key(3082, 34736, 1, 4),
            new Key(3083, 34736, 1, 5),
        }));
        double[] d = {42.0, 44.0, 3.0, 46.0, 1000000.0, 6000000.0, 6378137.0, 298.257222101004};
        Projection p = Crs.resolve(32767, custom(keys, d));
        assertNotNull(p);
        assertMatches(p, ALBERS);
        // an unverified model must still resolve to null (explicit failure)
        Key[] eqdc = concat(header(13), new Key[0]);
        assertNull(Crs.resolve(32767, custom(eqdc, new double[0])));
    }
}
