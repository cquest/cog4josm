package org.openstreetmap.josm.plugins.cog.crs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.cog.reader.CustomCrs;
import org.openstreetmap.josm.plugins.cog.reader.CustomCrs.Key;

/**
 * Check of the declarative mapping from "user-defined" GeoKeys (32767) to the
 * package's projections. References computed with GDAL/PROJ for systems
 * defined by PROJ.4 strings (custom_lcc / custom_tmerc from the reference
 * campaign): the GeoKeys parameters reproduce those GDAL writes in a GeoTIFF.
 */
class CustomCrsTest {

    /** Custom LCC references (GRS80, sp 44/49, lat0 46.5, lon0 3, FE 700000, FN 6600000). */
    private static final double[][] LCC = {
        {3.0, 46.5, 700000.000, 6600000.000},
        {2.294, 48.858, 648200.220, 6862227.533},
        {3.5, 47.2, 737852.329, 6677865.314},
    };

    /** Custom TM Clarke 1880 (IGN) references (lat0 48.85, lon0 2.35, k0 0.99986521). */
    private static final double[][] TMERC = {
        {2.35, 48.85, 600000.000, 2200000.000},
        {2.5, 49.0, 610974.823, 2216689.855},
        {2.2, 48.7, 588959.418, 2183332.288},
    };

    /**
     * Custom LCC keys; with {@code ellipsoidDoubles=false}, the
     * 2057/2059 ellipsoid parameters are absent and only code 2056=7019 replaces them.
     */
    private static CustomCrs lccKeys(boolean ellipsoidDoubles) {
        double[] d = {46.5, 3.0, 44.0, 49.0, 700000.0, 6600000.0, 298.257222101, 6378137.0, 0.0};
        Key[] base = {
            new Key(1024, 0, 1, 1),
            new Key(2048, 0, 1, 32767),
            new Key(2054, 0, 1, 9102),
            new Key(2056, 0, 1, 7019),
            new Key(3072, 0, 1, 32767),
            new Key(3074, 0, 1, 32767),
            new Key(3075, 0, 1, 8),
            new Key(3076, 0, 1, 9001),
            new Key(3078, 34736, 1, 2),
            new Key(3079, 34736, 1, 3),
            new Key(3084, 34736, 1, 1),
            new Key(3085, 34736, 1, 0),
            new Key(3086, 34736, 1, 4),
            new Key(3087, 34736, 1, 5),
        };
        Key[] all;
        if (ellipsoidDoubles) {
            all = new Key[base.length + 3];
            all[0] = new Key(2057, 34736, 1, 7);
            all[1] = new Key(2059, 34736, 1, 6);
            all[2] = new Key(2061, 34736, 1, 8);
        } else {
            all = new Key[base.length];
        }
        System.arraycopy(base, 0, all, all.length - base.length, base.length);
        return new CustomCrs(all, d, "GCS Name = unknown|");
    }

    private static CustomCrs tmKeys() {
        double[] d = {48.85, 2.35, 2.35, 0.99986521, 600000.0, 2200000.0, 6378249.2, 293.4660212936269, 0.0};
        // dbl[0]=lat0, dbl[1]=lon0 (3080), dbl[2]=lon0 bis, dbl[3]=k0, dbl[4]=FE, dbl[5]=FN
        // dbl[6]=a, dbl[7]=1/f, dbl[8]=PM
        Key[] keys = {
            new Key(1024, 0, 1, 1),
            new Key(2048, 0, 1, 32767),
            new Key(2054, 0, 1, 9102),
            new Key(2056, 0, 1, 32767),
            new Key(2057, 34736, 1, 6),
            new Key(2059, 34736, 1, 7),
            new Key(2061, 34736, 1, 8),
            new Key(3072, 0, 1, 32767),
            new Key(3074, 0, 1, 32767),
            new Key(3075, 0, 1, 1),
            new Key(3076, 0, 1, 9001),
            new Key(3080, 34736, 1, 2), // origin longitude
            new Key(3081, 34736, 1, 0), // origin latitude
            new Key(3082, 34736, 1, 4), // false easting
            new Key(3083, 34736, 1, 5), // false northing
            new Key(3092, 34736, 1, 3), // scale factor
        };
        return new CustomCrs(keys, d, null);
    }

    @Test
    void customLambertConformalMatchesGdal() {
        Projection p = Crs.fromCustom(lccKeys(true));
        assertTrue(p instanceof LambertConformalConic2SP);
        for (double[] c : LCC) {
            double[] xy = p.project(c[1], c[0]);
            assertEquals(c[2], xy[0], 0.001, "x(" + c[0] + "," + c[1] + ")");
            assertEquals(c[3], xy[1], 0.001, "y(" + c[0] + "," + c[1] + ")");
            double[] ll = p.unproject(c[2], c[3]);
            assertEquals(c[1], ll[0], 1e-8);
            assertEquals(c[0], ll[1], 1e-8);
        }
    }

    /** Without 2057/2059 DOUBLE parameters, the ellipsoid is found by its code 7019. */
    @Test
    void customEllipsoidByCode() {
        CustomCrs c = lccKeys(false);
        Ellipsoid e = Ellipsoid.forGeoKeyCode(7019);
        assertSame(Ellipsoid.GRS80, e);
        // projection identical to the one with explicit parameters (mm apart)
        Projection a = Crs.fromCustom(lccKeys(false));
        Projection b = Crs.fromCustom(lccKeys(true));
        for (double[] cse : LCC) {
            double[] xa = a.project(cse[1], cse[0]);
            assertEquals(cse[2], xa[0], 0.001);
            assertEquals(b.project(cse[1], cse[0])[0], xa[0], 1e-9);
        }
    }

    /** The custom LCC with Lambert-93 parameters must match EPSG:2154. */
    @Test
    void customLccEqualsRegisteredLambert93() {
        Projection custom = Crs.fromCustom(lccKeys(true));
        Projection l93 = Crs.forEpsg(2154);
        for (double[] cse : LCC) {
            double[] a = custom.project(cse[1], cse[0]);
            double[] b = l93.project(cse[1], cse[0]);
            assertEquals(b[0], a[0], 1e-6);
            assertEquals(b[1], a[1], 1e-6);
        }
    }

    @Test
    void customTransverseMercatorMatchesGdal() {
        Projection p = Crs.fromCustom(tmKeys());
        assertTrue(p instanceof TransverseMercator);
        for (double[] c : TMERC) {
            double[] xy = p.project(c[1], c[0]);
            assertEquals(c[2], xy[0], 0.001, "x(" + c[0] + "," + c[1] + ")");
            assertEquals(c[3], xy[1], 0.001, "y(" + c[0] + "," + c[1] + ")");
            double[] ll = p.unproject(c[2], c[3]);
            assertEquals(c[1], ll[0], 1e-8);
            assertEquals(c[0], ll[1], 1e-8);
        }
    }

    /** Custom geographic system with prime meridian at 2.5 degrees. */
    @Test
    void customGeographicWithPrimeMeridian() {
        Key[] keys = {
            new Key(1024, 0, 1, 2),
            new Key(2048, 0, 1, 32767),
            new Key(2054, 0, 1, 9102),
            new Key(2056, 0, 1, 7031),
            new Key(2061, 34736, 1, 0),
        };
        CustomCrs c = new CustomCrs(keys, new double[]{2.5}, null);
        Projection p = Crs.fromCustom(c);
        assertTrue(p instanceof Geographic);
        double[] xy = p.project(48.0, 3.0);
        assertEquals(0.5, xy[0], 1e-12);
        assertEquals(48.0, xy[1], 1e-12);
        double[] ll = p.unproject(0.5, 48.0);
        assertEquals(48.0, ll[0], 1e-12);
        assertEquals(3.0, ll[1], 1e-12);
    }

    /** Custom geographic system in degrees without special meridian: linear path OK. */
    @Test
    void customGeographicGreenwich() {
        Key[] keys = {
            new Key(1024, 0, 1, 2),
            new Key(2048, 0, 1, 32767),
            new Key(2056, 0, 1, 7001),
        };
        Projection p = Crs.fromCustom(new CustomCrs(keys, new double[0], null));
        assertTrue(p instanceof Geographic);
        assertTrue(((Geographic) p).isPrimeMeridianZero());
    }

    /** Lambert 1SP (3074=9): treated as 2SP with coincident parallels, explicit k0. */
    @Test
    void customLambert1sp() {
        double[] d = {45.0, 5.0, 1000000.0, 2000000.0, 6378137.0, 298.257223563, 0.99987731};
        Key[] keys = {
            new Key(1024, 0, 1, 1),
            new Key(2056, 0, 1, 7031),
            new Key(3072, 0, 1, 32767),
            new Key(3074, 0, 1, 9),
            new Key(3076, 0, 1, 9001),
            new Key(3080, 34736, 1, 1), // lon0
            new Key(3081, 34736, 1, 0), // lat0
            new Key(3082, 34736, 1, 2), // FE
            new Key(3083, 34736, 1, 3), // FN
            new Key(3092, 34736, 1, 6), // k0
        };
        Projection p = Crs.fromCustom(new CustomCrs(keys, d, null));
        // the central projected point (lat0, lon0) lands exactly on (FE, FN) by construction
        double[] xy = p.project(45.0, 5.0);
        assertEquals(1000000.0, xy[0], 1e-6, "exact FE/FN for lat0/lon0");
        assertEquals(2000000.0, xy[1], 1e-6);
        double[] ll = p.unproject(1000000.0, 2000000.0);
        assertEquals(45.0, ll[0], 1e-9);
        assertEquals(5.0, ll[1], 1e-9);
        // local consistency: a point to the north lands to the north (increasing y)
        double[] xy2 = p.project(46.0, 5.0);
        assertTrue(xy2[1] > xy[1]);
        assertEquals(1000000.0, xy2[0], 1e-6);
        // GDAL cross-reference: (45N, 6E) -> (1078835.160, 2000486.472)
        double[] xy3 = p.project(45.0, 6.0);
        assertEquals(1078835.160, xy3[0], 0.05);
        assertEquals(2000486.472, xy3[1], 0.05);
    }

    @Test
    void missingMethodRejected() {
        Key[] keys = {
            new Key(1024, 0, 1, 1),
            new Key(3072, 0, 1, 32767),
            new Key(3074, 0, 1, 32767),
        };
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Crs.fromCustom(new CustomCrs(keys, new double[0], null)));
        assertTrue(e.getMessage().contains("3074"));
    }

    @Test
    void unsupportedMethodRejected() {
        Key[] keys = {
            new Key(1024, 0, 1, 1),
            new Key(2056, 0, 1, 7031),
            new Key(3072, 0, 1, 32767),
            new Key(3074, 0, 1, 32767),
            new Key(3075, 0, 1, 6), // Albers: unsupported
        };
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Crs.fromCustom(new CustomCrs(keys, new double[0], null)));
        assertTrue(e.getMessage().contains("unsupported"));
    }

    @Test
    void missingEllipsoidRejected() {
        Key[] keys = {
            new Key(1024, 0, 1, 1),
            new Key(3072, 0, 1, 32767),
            new Key(3074, 0, 1, 1),
            new Key(3080, 34736, 1, 0),
            new Key(3081, 34736, 1, 1),
        };
        assertThrows(IllegalArgumentException.class,
                () -> Crs.fromCustom(new CustomCrs(keys, new double[]{2.0, 48.0}, null)));
    }

    @Test
    void nonDegreeAngularUnitsRejected() {
        Key[] keys = {
            new Key(1024, 0, 1, 2),
            new Key(2048, 0, 1, 32767),
            new Key(2054, 0, 1, 9105), // grad
        };
        assertThrows(IllegalArgumentException.class,
                () -> Crs.fromCustom(new CustomCrs(keys, new double[0], null)));
    }

    /** Non-metre linear units: the model coordinates are scaled. */
    @Test
    void linearUnitsScaled() {        double[] d = {46.5, 3.0, 44.0, 49.0, 700000.0, 6600000.0, 298.257222101, 6378137.0, 0.0};
        Key[] keys = {
            new Key(1024, 0, 1, 1),
            new Key(2056, 0, 1, 7019),
            new Key(3072, 0, 1, 32767),
            new Key(3074, 0, 1, 32767),
            new Key(3075, 0, 1, 8),
            new Key(3076, 0, 1, 9002), // international foot
            new Key(3078, 34736, 1, 2),
            new Key(3079, 34736, 1, 3),
            new Key(3084, 34736, 1, 1),
            new Key(3085, 34736, 1, 0),
            new Key(3086, 34736, 1, 4),
            new Key(3087, 34736, 1, 5),
        };
        Projection p = Crs.fromCustom(new CustomCrs(keys, d, null));
        double[] xy = p.project(46.5, 3.0);
        // false easting/northing GeoKeys are declared in the projection
        // linear units (feet here): the output at the false origin is exactly
        // those values (GDAL semantics, verified against gdal2tiles-style files)
        assertEquals(700000.0, xy[0], 1e-3, "feet expected");
        assertEquals(6600000.0, xy[1], 1e-3);
    }

    /**
     * PIX4Dmatic-style "hybrid" keys: 3072 carries a registered code
     * (EPSG:3943, Lambert-43 / CC43) but 3074=32767 guarantees that all the
     * method parameters are present as DOUBLE. GDAL references (osgeo,
     * ImportFromEPSG 3943): the described system is exactly CC43.
     */
    private static CustomCrs cc43Keys() {
        double[] d = {43.0, 3.0, 42.25, 43.75, 1700000.0, 2200000.0,
                298.257222101, 6378137.0, 0.0};
        Key[] keys = {
            new Key(1024, 0, 1, 1),
            new Key(2048, 0, 1, 4171),  // registered RGF93
            new Key(2054, 0, 1, 9102),
            new Key(2056, 0, 1, 7019),  // GRS80
            new Key(2057, 34736, 1, 7),
            new Key(2059, 34736, 1, 6),
            new Key(2061, 34736, 1, 8),
            new Key(3072, 0, 1, 3943),  // registered code (hybrid)
            new Key(3074, 0, 1, 32767), // user-defined method: parameters present
            new Key(3075, 0, 1, 8),
            new Key(3076, 0, 1, 9001),
            new Key(3078, 34736, 1, 2),
            new Key(3079, 34736, 1, 3),
            new Key(3084, 34736, 1, 1),
            new Key(3085, 34736, 1, 0),
            new Key(3086, 34736, 1, 4),
            new Key(3087, 34736, 1, 5),
        };
        return new CustomCrs(keys, d, "RGF93 v1 / CC43|");
    }

    @Test
    void hybridRegisteredCodeWithParamsMatchesGdal() {
        Projection p = Crs.fromCustom(cc43Keys());
        assertTrue(p instanceof LambertConformalConic2SP);
        double[][] cc43 = {
            {3.0, 43.0, 1700000.0000, 2200000.0000},
            {-1.4744, 43.4929, 1338271.6656, 2264391.2277},
            {2.5, 44.5, 1660225.1817, 2366784.2180},
        };
        for (double[] c : cc43) {
            double[] xy = p.project(c[1], c[0]);
            assertEquals(c[2], xy[0], 0.001, "x(" + c[0] + "," + c[1] + ")");
            assertEquals(c[3], xy[1], 0.001, "y(" + c[0] + "," + c[1] + ")");
            double[] ll = p.unproject(c[2], c[3]);
            assertEquals(c[1], ll[0], 1e-8);
            assertEquals(c[0], ll[1], 1e-8);
        }
    }

    /** Registered code WITHOUT 3074=32767: no parameters, keys judged inconsistent. */
    @Test
    void registeredCodeWithoutCustomTransRejected() {
        Key[] keys = {
            new Key(1024, 0, 1, 1),
            new Key(2048, 0, 1, 4171),
            new Key(3072, 0, 1, 2154), // Lambert-93 code without user-defined method
        };
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Crs.fromCustom(new CustomCrs(keys, new double[0], null)));
        assertTrue(e.getMessage().contains("registered"));
    }

    /** Crs.resolve: registry first, fall back to the GeoKeys parameters otherwise. */
    @Test
    void resolvePrefersRegistryThenParams() {
        // code in the registry: it wins, the parameters are ignored
        Projection l93 = Crs.resolve(2154, cc43Keys());
        assertSame(Crs.forEpsg(2154), l93);
        Projection zone43 = Crs.resolve(3943, cc43Keys());
        assertSame(Crs.forEpsg(3943), zone43);
        // code absent from the registry but parameters present: custom fallback
        Projection cc43 = Crs.resolve(3951, cc43Keys());
        assertTrue(cc43 instanceof LambertConformalConic2SP);
        double[] xy = cc43.project(43.0, 3.0);
        assertEquals(1700000.0, xy[0], 1e-6);
        assertEquals(2200000.0, xy[1], 1e-6);
        // neither a known code nor parameters: null (message carried by fromCustom)
        org.junit.jupiter.api.Assertions.assertNull(Crs.resolve(3951, null));
        org.junit.jupiter.api.Assertions.assertNull(Crs.resolve(0, null));
    }
}
