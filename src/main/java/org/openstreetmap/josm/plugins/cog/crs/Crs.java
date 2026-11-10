package org.openstreetmap.josm.plugins.cog.crs;

import java.util.Locale;

import org.openstreetmap.josm.plugins.cog.reader.CustomCrs;

/**
 * Registre des projections supportees par le plugin.
 *
 * Volumes pris en charge :
 * <ul>
 *   <li>EPSG:4326 (geographique WGS84) et EPSG:3857 (Web Mercator) ;</li>
 *   <li>UTM WGS84 : zones nord 32601-32660, zones sud 32701-32760 ;</li>
 *   <li>EPSG:2154 (RGF97 / Lambert-93, France metropolitaine) ;</li>
 *   <li>systemes "user-defined" des GeoKeys GeoTIFF (codes 32767) lorsque
 *       leurs parametres correspondent a un modele connu : geographique,
 *       transverse de Mercator ou conique conforme de Lambert (phase 2.1,
 *       voir {@link #fromCustom}).</li>
 * </ul>
 * Les datums non-WGS84 (NTF, SAD69...) sont traites comme WGS84 : erreur
 * metrologique de l'ordre de la centaine de metres, acceptable pour un fond
 * d'carte mais documentee. Les systemes custom sont en revanche projetes sur
 * leur propre ellipsoide (pas de datum shift, meme remarque).
 */
public final class Crs {

    private static final java.util.Map<Integer, Projection> CACHE = new java.util.LinkedHashMap<>();

    private Crs() {
    }

    /** Projection pour ce code EPSG, ou null si non supportee. */
    public static synchronized Projection forEpsg(int epsg) {
        Projection p = CACHE.get(epsg);
        if (p == null) {
            p = create(epsg);
            if (p != null) {
                CACHE.put(epsg, p);
            }
        }
        return p;
    }

    public static boolean isSupported(int epsg) {
        return forEpsg(epsg) != null;
    }

    private static Projection create(int epsg) {
        if (epsg == 4326) {
            return new Geographic(4326);
        }
        if (epsg == 3857) {
            return new WebMercator();
        }
        if (epsg >= 32601 && epsg <= 32660) {
            return utm(epsg, epsg - 32600, false);
        }
        if (epsg >= 32701 && epsg <= 32760) {
            return utm(epsg, epsg - 32700, true);
        }
        if (epsg == 2154) {
            // RGF97 (GRS80) / Lambert-93
            return new LambertConformalConic2SP(2154, Ellipsoid.GRS80,
                    44.0, 49.0, 46.5, 3.0, 700000.0, 6600000.0);
        }
        return null;
    }

    private static Projection utm(int epsg, int zone, boolean south) {
        if (zone < 1 || zone > 60) {
            return null;
        }
        return new TransverseMercator(epsg, Ellipsoid.WGS84,
                0.9996, 6.0 * zone - 183.0, 0.0, 500000.0, south ? 10000000.0 : 0.0);
    }

    // ------------------------------------------------ systemes "user-defined"

    /**
     * Convertit les GeoKeys "user-defined" (codes 32767) d'un COG en projection
     * du paquetage : mapping declaratif des parametres GeoTIFF (degres,
     * metres) vers les constructeurs existants ({@link Geographic},
     * {@link TransverseMercator}, {@link LambertConformalConic2SP}). Aucune
     * nouvelle formule de projection n'est introduite ; un systeme dont le
     * modele n'est pas representable par ces classes leve une
     * IllegalArgumentException explicite (jamais de reprojection implicite).
     *
     * Le datum est ignore (traites comme le systeme de 1er ordre) : precision
     * documentee, comparable au cas des codes EPSG non-WGS84.
     *
     * @return la projection resolue
     * @throws IllegalArgumentException si les clefs sont incompletes ou decrivent
     *         un modele non supporte
     */
    public static Projection fromCustom(CustomCrs c) {
        if (c == null) {
            throw new IllegalArgumentException("definition custom absente");
        }
        Integer model = c.key(CustomCrs.KEY_MODEL_TYPE);
        if (model != null && model == CustomCrs.MODEL_TYPE_GEOGRAPHIC) {
            return geographic(c);
        }
        if (model == null || model == 1) {
            return projected(c);
        }
        throw new IllegalArgumentException("modele GeoTIFF 1024=" + model + " non supporte");
    }

    private static Projection geographic(CustomCrs c) {
        Integer gcs = c.key(CustomCrs.KEY_GEOG_CODE);
        if (gcs != null && gcs != CustomCrs.USER_DEFINED) {
            throw new IllegalArgumentException("GCS enregistre " + gcs + " : clefs custom incoherentes");
        }
        Integer units = c.key(CustomCrs.KEY_GEOG_AZIMUTH_UNITS);
        if (units != null && units != CustomCrs.UNIT_DEGREE) {
            throw new IllegalArgumentException("unites angulaires GeoKey 2054=" + units
                    + " : seuls les degres sont supportes");
        }
        double pm = optDouble(c, CustomCrs.KEY_GEOG_PRIME_MERIDIAN, 0.0);
        if (!(Math.abs(pm) <= 180.0)) {
            throw new IllegalArgumentException("meridien principal (2061) hors bornes : " + pm);
        }
        ellipsoid(c); // valide les parametres d'ellipsoide (non utilise pour x/y en degres)
        return new Geographic(0, pm);
    }

    private static Projection projected(CustomCrs c) {
        Integer pcs = c.key(CustomCrs.KEY_PROJ_CODE);
        if (pcs != null && pcs != CustomCrs.USER_DEFINED) {
            throw new IllegalArgumentException("projection enregistree " + pcs
                    + " : clefs custom incoherentes");
        }
        Integer coordTrans = c.key(CustomCrs.KEY_PROJ_COORD_TRANS);
        Integer methodCode = c.key(CustomCrs.KEY_PROJ_METHOD_CODE);
        int method = resolveMethod(coordTrans, methodCode);

        Integer unitsKey = c.key(CustomCrs.KEY_PROJ_LINEAR_UNITS);
        int units = unitsKey == null ? 9001 : unitsKey;
        Double unitFactor = CustomCrs.linearToMetre(units);
        if (unitFactor == null) {
            throw new IllegalArgumentException("unites lineaires GeoKey 3076=" + units + " non supportees");
        }
        Double k0 = c.dbl(CustomCrs.KEY_PROJ_SCALE_FACTOR);
        if (k0 != null && (k0 <= 0 || !Double.isFinite(k0))) {
            throw new IllegalArgumentException("facteur d'echelle (3092) invalide : " + k0);
        }
        Ellipsoid ell = ellipsoid(c);

        Projection p;
        switch (method) {
            case CustomCrs.CT_TRANSVERSE_MERCATOR: {
                double lat0 = reqDouble(c, CustomCrs.KEY_PROJ_ORIG_LAT, 3081);
                double lon0 = reqDouble(c, CustomCrs.KEY_PROJ_CENT_MERIDIAN, 3080);
                double fe = optDouble(c, CustomCrs.KEY_PROJ_FALSE_EASTING, 0.0);
                double fn = optDouble(c, CustomCrs.KEY_PROJ_FALSE_NORTHING, 0.0);
                checkLatLon(lat0, lon0);
                p = new TransverseMercator(0, ell, k0 == null ? 1.0 : k0, lon0, lat0, fe, fn);
                break;
            }
            case CustomCrs.CT_LAMBERT_CONFORMAL_2SP: {
                double sp1 = reqDouble(c, CustomCrs.KEY_PROJ_STD_PARALLEL1, 3078);
                double sp2 = optDouble(c, CustomCrs.KEY_PROJ_STD_PARALLEL2, sp1);
                double lat0 = optDouble(c, CustomCrs.KEY_PROJ_FALSE_ORIGIN_LAT, sp1);
                double lon0 = optDouble(c, CustomCrs.KEY_PROJ_FALSE_ORIGIN_LONG, 0.0);
                double fe = optDouble(c, CustomCrs.KEY_PROJ_FALSE_ORIGIN_EASTING, 0.0);
                double fn = optDouble(c, CustomCrs.KEY_PROJ_FALSE_ORIGIN_NORTHING, 0.0);
                checkLatLon(lat0, lon0);
                checkLatLon(sp1, 0);
                checkLatLon(sp2, 0);
                if (Math.abs(sp1) < 1e-12 && Math.abs(sp2) < 1e-12) {
                    throw new IllegalArgumentException("paralleles d'entree/sortie nuls (3078/3079)");
                }
                p = new LambertConformalConic2SP(0, ell, sp1, sp2, lat0, lon0, fe, fn, k0);
                break;
            }
            case CustomCrs.CT_LAMBERT_CONFORMAL_1SP:
            case CustomCrs.M_LAMBERT_CONFORMAL_1SP: {
                double lat0 = reqDouble(c, CustomCrs.KEY_PROJ_ORIG_LAT, 3081);
                double lon0 = optDouble(c, CustomCrs.KEY_PROJ_CENT_MERIDIAN, 0.0);
                double fe = optDouble(c, CustomCrs.KEY_PROJ_FALSE_EASTING, 0.0);
                double fn = optDouble(c, CustomCrs.KEY_PROJ_FALSE_NORTHING, 0.0);
                checkLatLon(lat0, lon0);
                // Lambert 1SP = 2SP avec paralleles confondus ; k0 = 1 sauf mention
                p = new LambertConformalConic2SP(0, ell, lat0, lat0, lat0, lon0, fe, fn,
                        k0 == null ? 1.0 : k0);
                break;
            }
            default:
                throw new IllegalArgumentException("modele de projection non supporte : "
                        + describeMethod(coordTrans, methodCode));
        }
        if (unitFactor == 1.0) {
            return p;
        }
        return new Scaled(p, unitFactor);
    }

    /** Reconcilie le type de transformation (3074) et le code de methode (3075). */
    private static int resolveMethod(Integer coordTrans, Integer methodCode) {
        if (coordTrans != null && coordTrans != CustomCrs.USER_DEFINED) {
            return coordTrans;
        }
        if (methodCode != null) {
            return methodCode;
        }
        throw new IllegalArgumentException("modele de projection non declare (GeoKeys 3074/3075 absents)");
    }

    private static String describeMethod(Integer coordTrans, Integer methodCode) {
        return "3074=" + coordTrans + ", 3075=" + methodCode;
    }

    /**
     * Ellipsoide des clefs custom : parametres 2057/2059 en priorite, sinon
     * code d'ellipsoide (2056) ou de datum (2050) connu de la table GeoTIFF.
     */
    private static Ellipsoid ellipsoid(CustomCrs c) {
        Double a = c.dbl(CustomCrs.KEY_GEOG_SEMI_MAJOR);
        Double rf = c.dbl(CustomCrs.KEY_GEOG_INV_FLATTENING);
        if (a != null && rf != null) {
            if (!Double.isFinite(a) || !Double.isFinite(rf) || a <= 0 || rf < 0) {
                throw new IllegalArgumentException("ellipsoide custom invalide : a=" + a + ", 1/f=" + rf);
            }
            return Ellipsoid.of(a, rf);
        }
        Integer code = c.key(CustomCrs.KEY_GEOG_ELLIPSOID);
        if (code == null || code == CustomCrs.USER_DEFINED) {
            code = c.key(CustomCrs.KEY_GEOG_DATUM);
        }
        if (code != null && code != CustomCrs.USER_DEFINED) {
            Ellipsoid e = Ellipsoid.forGeoKeyCode(code);
            if (e != null) {
                return e;
            }
            throw new IllegalArgumentException("code d'ellipsoide GeoTIFF inconnu : " + code);
        }
        throw new IllegalArgumentException("ellipsoide non define (GeoKeys 2056/2057/2059 absents)");
    }

    private static double reqDouble(CustomCrs c, int id, int shownId) {
        Double v = c.dbl(id);
        if (v == null || !Double.isFinite(v)) {
            throw new IllegalArgumentException("GeoKey " + shownId + " (DOUBLE) absent ou invalide");
        }
        return v;
    }

    private static double optDouble(CustomCrs c, int id, double deflt) {
        Double v = c.dbl(id);
        return v == null || !Double.isFinite(v) ? deflt : v;
    }

    private static void checkLatLon(double lat, double lon) {
        if (!(lat >= -90 && lat <= 90) || !(lon >= -540 && lon <= 540)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "parametres angulaires hors bornes : lat=%s, lon=%s", lat, lon));
        }
    }

    /** Adaptateur qui met a l'echelle metres les coordonnees du systeme source. */
    private static final class Scaled implements Projection {
        private final Projection base;
        private final double toMetre;

        Scaled(Projection base, double toMetre) {
            this.base = base;
            this.toMetre = toMetre;
        }

        @Override
        public double[] project(double lat, double lon) {
            double[] xy = base.project(lat, lon);
            return new double[]{xy[0] / toMetre, xy[1] / toMetre};
        }

        @Override
        public double[] unproject(double x, double y) {
            return base.unproject(x * toMetre, y * toMetre);
        }

        @Override
        public int epsg() {
            return 0;
        }
    }
}
