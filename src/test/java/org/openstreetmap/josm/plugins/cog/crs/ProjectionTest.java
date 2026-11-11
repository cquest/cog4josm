package org.openstreetmap.josm.plugins.cog.crs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Check of the in-house projections against reference values computed
 * with GDAL/PROJ (osgeo.osr CoordinateTransformation, traditional lon/lat order).
 */
class ProjectionTest {

    /** {lon, lat, x, y} - EPSG:32631 references (UTM 31N, WGS84). */
    private static final double[][] UTM31N = {
        {2.35, 48.85, 452314.891, 5410984.888},
        {2.294, 48.858, 448214.914, 5411910.786},
        {2.25, 48.80, 444924.007, 5405494.349},
        {2.45, 48.92, 459707.306, 5418708.402},
        {2.0, 49.5, 427592.990, 5483521.328},
        {3.0, 48.0, 500000.000, 5316300.224},
        {2.35, 46.0, 449669.032, 5094252.863},
    };

    /** EPSG:32733 references (UTM 33S, southern hemisphere, WGS84). */
    private static final double[][] UTM33S = {
        {17.5, -33.0, 733569.963, 6345936.665},
        {18.4, -34.35, 812756.208, 6193795.921},
        {20.5, -30.5, 1028178.639, 6612917.138},
        {15.0, -25.0, 500000.000, 7235052.253},
    };

    /** EPSG:2154 references (Lambert-93, RGF97 / GRS80). */
    private static final double[][] LAMBERT93 = {
        {2.294, 48.858, 648200.220, 6862227.533},
        {2.35, 48.85, 652301.565, 6861302.726},
        {4.835, 45.76, 842622.588, 6519479.019},
        {-4.49, 48.39, 146341.347, 6836245.404},
        {7.75, 48.58, 1050163.944, 6841622.715},
        {1.44, 43.61, 574030.320, 6280238.590},
        {6.0, 43.0, 944782.552, 6215834.245},
        {3.0, 46.5, 700000.000, 6600000.000},
    };

    /** EPSG:3942-3950 anchors (RGF97 / Lambert-93 zones CC42-CC50, gdaltransform). */
    private static final double[][] LAMBERT93_ZONES = {
        {1.5, 42.5, 1576713.6510, 1256614.9390},
        {4.5, 42.5, 1823286.3490, 1256614.9390},
        {1.5, 43.5, 1578697.1976, 2256627.7312},
        {4.5, 43.5, 1821302.8024, 2256627.7312},
        {1.5, 44.5, 1580717.9076, 3256639.2164},
        {4.5, 44.5, 1819282.0924, 3256639.2164},
        {1.5, 45.5, 1582775.1785, 4256649.3803},
        {4.5, 45.5, 1817224.8215, 4256649.3803},
        {1.5, 46.5, 1584868.3959, 5256658.2104},
        {4.5, 46.5, 1815131.6041, 5256658.2104},
        {1.5, 47.5, 1586996.9338, 6256665.6956},
        {4.5, 47.5, 1813003.0662, 6256665.6956},
        {1.5, 48.5, 1589160.1547, 7256671.8266},
        {4.5, 48.5, 1810839.8453, 7256671.8266},
        {1.5, 49.5, 1591357.4101, 8256676.5957},
        {4.5, 49.5, 1808642.5899, 8256676.5957},
        {1.5, 50.5, 1593588.0401, 9256679.9968},
        {4.5, 50.5, 1806411.9599, 9256679.9968},
    };

    private static void check(String what, Projection p, double[][] cases, double tol, double tolInv) {
        for (double[] c : cases) {
            double[] xy = p.project(c[1], c[0]);
            assertEquals(c[2], xy[0], tol, what + " x(" + c[0] + "," + c[1] + ")");
            assertEquals(c[3], xy[1], tol, what + " y(" + c[0] + "," + c[1] + ")");
            double[] ll = p.unproject(c[2], c[3]);
            assertEquals(c[1], ll[0], tolInv, what + " lat inverse(" + c[2] + "," + c[3] + ")");
            assertEquals(c[0], ll[1], tolInv, what + " lon inverse(" + c[2] + "," + c[3] + ")");
        }
    }

    private static void check(String what, Projection p, double[][] cases, double tol) {
        check(what, p, cases, tol, 1e-8);
    }

    @Test
    void utm31n() {
        check("UTM31N", Crs.forEpsg(32631), UTM31N, 0.001);
    }

    @Test
    void utm33s() {
        // widened tolerance: Snyder series truncated at 6th order, ~3 mm in x
        // and ~2e-7 deg in inverse longitude at 5 degrees from the central meridian
        // (the 20.5/-30.5 point is outside zone 33)
        check("UTM33S", Crs.forEpsg(32733), UTM33S, 0.01, 1e-5);
    }

    @Test
    void lambert93() {
        check("Lambert93", Crs.forEpsg(2154), LAMBERT93, 0.001);
    }

    /**
     * Each zone CC42-CC50 (EPSG:3942-3950) is checked with its two anchors in
     * LAMBERT93_ZONES: rows 2*(z-42) and 2*(z-42)+1.
     */
    @Test
    void lambert93zones() {
        for (int epsg = 3942; epsg <= 3950; epsg++) {
            int z = epsg - 3900;
            double[][] anchors = {
                {3.0, z, 1700000.0, (z - 41.0) * 1000000.0 + 200000.0}, // origin, gdaltransform exact
                LAMBERT93_ZONES[2 * (z - 42)],
                LAMBERT93_ZONES[2 * (z - 42) + 1],
            };
            check("Lambert93 CC" + z, Crs.forEpsg(epsg), anchors, 0.001);
        }
    }

    /** EPSG:3857 references (Web Mercator, sphere a=6378137). */
    @Test
    void webMercator() {
        check("WebMerc", Crs.forEpsg(3857), new double[][] {
            {2.35, 48.85, 261600.8034, 6249447.7528},
            {2.25, 48.80, 250468.8543, 6240993.4635},
            {2.45, 48.92, 272732.7524, 6261297.9534},
            {180.0, 0.0, 20037508.3428, 0.0000},
        }, 0.001);
    }

    @Test
    void registry() {
        org.junit.jupiter.api.Assertions.assertNotNull(Crs.forEpsg(32601));
        org.junit.jupiter.api.Assertions.assertNotNull(Crs.forEpsg(32660));
        org.junit.jupiter.api.Assertions.assertNotNull(Crs.forEpsg(32701));
        org.junit.jupiter.api.Assertions.assertNotNull(Crs.forEpsg(32760));
        org.junit.jupiter.api.Assertions.assertNull(Crs.forEpsg(2154 - 1));
        org.junit.jupiter.api.Assertions.assertNull(Crs.forEpsg(4326 + 1));
        org.junit.jupiter.api.Assertions.assertTrue(Crs.isSupported(4326));
        org.junit.jupiter.api.Assertions.assertTrue(Crs.isSupported(2154));
        org.junit.jupiter.api.Assertions.assertTrue(Crs.isSupported(3942));
        org.junit.jupiter.api.Assertions.assertTrue(Crs.isSupported(3950));
        org.junit.jupiter.api.Assertions.assertNull(Crs.forEpsg(3942 - 1));
        org.junit.jupiter.api.Assertions.assertNull(Crs.forEpsg(3950 + 1));
        org.junit.jupiter.api.Assertions.assertFalse(Crs.isSupported(3035));
        double[] ll = Crs.forEpsg(4326).unproject(2.35, 48.85);
        org.junit.jupiter.api.Assertions.assertEquals(48.85, ll[0], 0.0);
        org.junit.jupiter.api.Assertions.assertEquals(2.35, ll[1], 0.0);
    }
}
