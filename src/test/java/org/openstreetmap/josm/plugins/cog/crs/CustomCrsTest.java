package org.openstreetmap.josm.plugins.cog.crs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.cog.reader.CustomCrs;
import org.openstreetmap.josm.plugins.cog.reader.CustomCrs.Key;

/**
 * Controle du mapping declaratif des GeoKeys "user-defined" (32767) vers les
 * projections du paquetage. References calculees avec GDAL/PROJ pour des
 * systemes definis par chaine PROJ.4 (custom_lcc / custom_tmerc de la
 * campagne de reference) : les parametres GeoKeys reproduisent ceux que
 * GDAL ecrit dans un GeoTIFF.
 */
class CustomCrsTest {

    /** References du LCC custom (GRS80, sp 44/49, lat0 46.5, lon0 3, FE 700000, FN 6600000). */
    private static final double[][] LCC = {
        {3.0, 46.5, 700000.000, 6600000.000},
        {2.294, 48.858, 648200.220, 6862227.533},
        {3.5, 47.2, 737852.329, 6677865.314},
    };

    /** References du TM custom Clarke 1880 (IGN) (lat0 48.85, lon0 2.35, k0 0.99986521). */
    private static final double[][] TMERC = {
        {2.35, 48.85, 600000.000, 2200000.000},
        {2.5, 49.0, 610974.823, 2216689.855},
        {2.2, 48.7, 588959.418, 2183332.288},
    };

    /**
     * Clefs LCC custom ; avec {@code ellipsoidDoubles=false}, les parametres
     * d'ellipsoide 2057/2059 sont absents et seul le code 2056=7019 les remplace.
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
            new Key(3080, 34736, 1, 2), // longitude d'origine
            new Key(3081, 34736, 1, 0), // latitude d'origine
            new Key(3082, 34736, 1, 4), // fausse est
            new Key(3083, 34736, 1, 5), // fausse nord
            new Key(3092, 34736, 1, 3), // facteur d'echelle
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

    /** Sans parametres DOUBLE 2057/2059, l'ellipsoide est trouve par son code 7019. */
    @Test
    void customEllipsoidByCode() {
        CustomCrs c = lccKeys(false);
        Ellipsoid e = Ellipsoid.forGeoKeyCode(7019);
        assertSame(Ellipsoid.GRS80, e);
        // projection identique a celle avec parametres explicites (mm pres)
        Projection a = Crs.fromCustom(lccKeys(false));
        Projection b = Crs.fromCustom(lccKeys(true));
        for (double[] cse : LCC) {
            double[] xa = a.project(cse[1], cse[0]);
            assertEquals(cse[2], xa[0], 0.001);
            assertEquals(b.project(cse[1], cse[0])[0], xa[0], 1e-9);
        }
    }

    /** Le LCC custom aux parametres de Lambert-93 doit coincider avec EPSG:2154. */
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

    /** Systeme geographique custom avec meridien principal a 2.5 degres. */
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

    /** Systeme geographique custom en degres sans meridien special : voie lineaire OK. */
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

    /** Lambert 1SP (3074=9) : traite comme 2SP a paralleles confondus, k0 explicite. */
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
        // le projet central (lat0, lon0) tombe exactement sur (FE, FN) par construction
        double[] xy = p.project(45.0, 5.0);
        assertEquals(1000000.0, xy[0], 1e-6, "lat0/lon0 -> FE/FN exacts");
        assertEquals(2000000.0, xy[1], 1e-6);
        double[] ll = p.unproject(1000000.0, 2000000.0);
        assertEquals(45.0, ll[0], 1e-9);
        assertEquals(5.0, ll[1], 1e-9);
        // coherence locale : un point au nord tombe au nord (y croissant)
        double[] xy2 = p.project(46.0, 5.0);
        assertTrue(xy2[1] > xy[1]);
        assertEquals(1000000.0, xy2[0], 1e-6);
        // contre-reference GDAL : (45N, 6E) -> (1078835.160, 2000486.472)
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
            new Key(3075, 0, 1, 6), // Albers : non supporte
        };
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Crs.fromCustom(new CustomCrs(keys, new double[0], null)));
        assertTrue(e.getMessage().contains("non supporte"));
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

    /** Unites lineaires non metres : les coordonnees modeles sont mises a l'echelle. */
    @Test
    void linearUnitsScaled() {
        double[] d = {46.5, 3.0, 44.0, 49.0, 700000.0, 6600000.0, 298.257222101, 6378137.0, 0.0};
        Key[] keys = {
            new Key(1024, 0, 1, 1),
            new Key(2056, 0, 1, 7019),
            new Key(3072, 0, 1, 32767),
            new Key(3074, 0, 1, 32767),
            new Key(3075, 0, 1, 8),
            new Key(3076, 0, 1, 9002), // pied international
            new Key(3078, 34736, 1, 2),
            new Key(3079, 34736, 1, 3),
            new Key(3084, 34736, 1, 1),
            new Key(3085, 34736, 1, 0),
            new Key(3086, 34736, 1, 4),
            new Key(3087, 34736, 1, 5),
        };
        Projection p = Crs.fromCustom(new CustomCrs(keys, d, null));
        double[] xy = p.project(46.5, 3.0);
        assertEquals(700000.0 / 0.3048, xy[0], 1e-3, "pieds attendus");
        assertEquals(6600000.0 / 0.3048, xy[1], 1e-3);
    }
}
