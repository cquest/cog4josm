package org.openstreetmap.josm.plugins.cog.crs;

import java.util.Locale;

import org.openstreetmap.josm.plugins.cog.reader.CustomCrs;

/**
 * Registry of projections supported by the plugin.
 *
 * Covered scopes:
 * <ul>
 *   <li>EPSG:4326 (WGS84 geographic) and EPSG:3857 (Web Mercator), built
 *       in;</li>
 *   <li>any EPSG code listed in the projection definitions shipped by the
 *       host application (JOSM's data/projection/custom-epsg), when its
 *       model is one the plugin computes: geographic, transverse Mercator
 *       and UTM, Lambert conformal conic 1SP/2SP, plus the verified proj4j
 *       subset (Albers, LAEA, stereographic) - see {@link JosmEpsgTable};</li>
 *   <li>GeoTIFF GeoKey "user-defined" systems (codes 32767) when their
 *       parameters match a known model: geographic, transverse Mercator or
 *       Lambert conformal conic (phase 2.1, see {@link #fromCustom}).</li>
 * </ul>
 * Non-WGS84 datums (NTF, SAD69...) are treated as WGS84: metrology error on
 * the order of a hundred metres, acceptable for a background map but
 * documented. Custom systems are on the other hand projected onto their own
 * ellipsoid (no datum shift, same caveat).
 */
public final class Crs {

    private static final java.util.Map<Integer, Projection> CACHE = new java.util.LinkedHashMap<>();

    private Crs() {
    }

    /** Projection for this EPSG code, or null when unsupported. */
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

    /**
     * Projection declared by a COG: registered EPSG code when the registry
     * knows it, otherwise fallback on the "user-defined" GeoKeys parameters
     * (3074=32767) that some writers attach to an unlisted code. Null when
     * no system can be resolved; the fallback failure reason is obtained by
     * calling {@link #fromCustom} again (IllegalArgumentException).
     */
    public static Projection resolve(int crsCode, CustomCrs custom) {
        if (crsCode != 0 && crsCode != CustomCrs.USER_DEFINED) {
            Projection p = forEpsg(crsCode);
            if (p != null) {
                return p;
            }
        }
        if (custom == null) {
            return null;
        }
        try {
            return fromCustom(custom);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Projection create(int epsg) {
        if (epsg == 4326) {
            return new Geographic(4326);
        }
        if (epsg == 3857) {
            return new WebMercator();
        }
        // every other registered code: the projection definitions shipped by
        // the host application (data/projection/custom-epsg), limited to the
        // models the plugin knows how to compute (see JosmEpsgTable)
        return JosmEpsgTable.create(epsg);
    }

    /**
     * Why a code could not be resolved by the host projection table (GUI
     * error detail). Never null.
     */
    public static String tableReason(int epsg) {
        return JosmEpsgTable.reason(epsg);
    }

    // ------------------------------------------------ "user-defined" systems

    /**
     * Converts a COG's "user-defined" GeoKeys (codes 32767) into a package
     * projection: declarative mapping of GeoTIFF parameters (degrees, metres)
     * onto the existing constructors ({@link Geographic},
     * {@link TransverseMercator}, {@link LambertConformalConic2SP}). No new
     * projection formula is introduced; a system whose model cannot be
     * represented by these classes raises an explicit IllegalArgumentException
     * (never an implicit reprojection).
     *
     * The datum is ignored (systems are treated as first-order): documented
     * accuracy, comparable to the non-WGS84 EPSG code case.
     *
     * @return the resolved projection
     * @throws IllegalArgumentException if the keys are incomplete or describe
     *         an unsupported model
     */
    public static Projection fromCustom(CustomCrs c) {
        if (c == null) {
            throw new IllegalArgumentException("missing custom definition");
        }
        Integer model = c.key(CustomCrs.KEY_MODEL_TYPE);
        if (model != null && model == CustomCrs.MODEL_TYPE_GEOGRAPHIC) {
            return geographic(c);
        }
        if (model == null || model == 1) {
            return projected(c);
        }
        throw new IllegalArgumentException("unsupported GeoTIFF model 1024=" + model);
    }

    private static Projection geographic(CustomCrs c) {
        Integer gcs = c.key(CustomCrs.KEY_GEOG_CODE);
        if (gcs != null && gcs != CustomCrs.USER_DEFINED) {
            throw new IllegalArgumentException("registered GCS " + gcs + ": inconsistent custom keys");
        }
        Integer units = c.key(CustomCrs.KEY_GEOG_AZIMUTH_UNITS);
        if (units != null && units != CustomCrs.UNIT_DEGREE) {
            throw new IllegalArgumentException("angular units GeoKey 2054=" + units
                    + ": only degrees are supported");
        }
        double pm = optDouble(c, CustomCrs.KEY_GEOG_PRIME_MERIDIAN, 0.0);
        if (!(Math.abs(pm) <= 180.0)) {
            throw new IllegalArgumentException("principal meridian (2061) out of range: " + pm);
        }
        ellipsoid(c); // validates ellipsoid parameters (unused for x/y in degrees)
        return new Geographic(0, pm);
    }

    private static Projection projected(CustomCrs c) {
        Integer pcs = c.key(CustomCrs.KEY_PROJ_CODE);
        Integer coordTrans = c.key(CustomCrs.KEY_PROJ_COORD_TRANS);
        if (pcs != null && pcs != CustomCrs.USER_DEFINED
                && (coordTrans == null || coordTrans != CustomCrs.USER_DEFINED)) {
            // registered code without a "user-defined" method: no usable
            // parameters, this is a plain GeoTIFF key misrouted here
            throw new IllegalArgumentException("registered projection code " + pcs
                    + ": inconsistent custom keys");
        }
        // otherwise: 3074=32767 guarantees the method parameters are present
        // (GDAL/PIX4D convention), even when 3072 holds a registered code (hybrid)
        Integer methodCode = c.key(CustomCrs.KEY_PROJ_METHOD_CODE);
        int method = resolveMethod(coordTrans, methodCode);

        Integer unitsKey = c.key(CustomCrs.KEY_PROJ_LINEAR_UNITS);
        int units = unitsKey == null ? 9001 : unitsKey;
        Double unitFactor = CustomCrs.linearToMetre(units);
        if (unitFactor == null) {
            throw new IllegalArgumentException("unsupported linear units GeoKey 3076=" + units);
        }
        Double k0 = c.dbl(CustomCrs.KEY_PROJ_SCALE_FACTOR);
        if (k0 != null && (k0 <= 0 || !Double.isFinite(k0))) {
            throw new IllegalArgumentException("invalid scale factor (3092): " + k0);
        }
        Ellipsoid ell = ellipsoid(c);

        Projection p;
        // false easting/northing GeoKeys are expressed in the projection
        // linear units (3076): convert to metres for the constructors, the
        // Scaled wrapper below turns the output back into those units
        switch (method) {
            case CustomCrs.CT_TRANSVERSE_MERCATOR: {
                double lat0 = reqDouble(c, CustomCrs.KEY_PROJ_ORIG_LAT, 3081);
                double lon0 = reqDouble(c, CustomCrs.KEY_PROJ_CENT_MERIDIAN, 3080);
                double fe = optDouble(c, CustomCrs.KEY_PROJ_FALSE_EASTING, 0.0) * unitFactor;
                double fn = optDouble(c, CustomCrs.KEY_PROJ_FALSE_NORTHING, 0.0) * unitFactor;
                checkLatLon(lat0, lon0);
                p = new TransverseMercator(0, ell, k0 == null ? 1.0 : k0, lon0, lat0, fe, fn);
                break;
            }
            case CustomCrs.CT_LAMBERT_CONFORMAL_2SP: {
                double sp1 = reqDouble(c, CustomCrs.KEY_PROJ_STD_PARALLEL1, 3078);
                double sp2 = optDouble(c, CustomCrs.KEY_PROJ_STD_PARALLEL2, sp1);
                double lat0 = optDouble(c, CustomCrs.KEY_PROJ_FALSE_ORIGIN_LAT, sp1);
                double lon0 = optDouble(c, CustomCrs.KEY_PROJ_FALSE_ORIGIN_LONG, 0.0);
                double fe = optDouble(c, CustomCrs.KEY_PROJ_FALSE_ORIGIN_EASTING, 0.0) * unitFactor;
                double fn = optDouble(c, CustomCrs.KEY_PROJ_FALSE_ORIGIN_NORTHING, 0.0) * unitFactor;
                checkLatLon(lat0, lon0);
                checkLatLon(sp1, 0);
                checkLatLon(sp2, 0);
                if (Math.abs(sp1) < 1e-12 && Math.abs(sp2) < 1e-12) {
                    throw new IllegalArgumentException("null standard parallels (3078/3079)");
                }
                p = new LambertConformalConic2SP(0, ell, sp1, sp2, lat0, lon0, fe, fn, k0);
                break;
            }
            case CustomCrs.CT_LAMBERT_CONFORMAL_1SP:
            case CustomCrs.M_LAMBERT_CONFORMAL_1SP: {
                if (method == CustomCrs.M_LAMBERT_CONFORMAL_1SP
                        && c.find(CustomCrs.KEY_PROJ_STD_PARALLEL1) == null
                        && c.find(CustomCrs.KEY_PROJ_STD_PARALLEL2) == null) {
                    // 3075=14 without standard parallels: GDAL means oblique
                    // Stereographic (natural origin 3088/3089), not Lambert 1SP
                    p = Proj4Backend.create(c, CustomCrs.M_STEREOGRAPHIC, ell, unitFactor);
                    break;
                }
                double lat0 = reqDouble(c, CustomCrs.KEY_PROJ_ORIG_LAT, 3081);
                double lon0 = optDouble(c, CustomCrs.KEY_PROJ_CENT_MERIDIAN, 0.0);
                double fe = optDouble(c, CustomCrs.KEY_PROJ_FALSE_EASTING, 0.0) * unitFactor;
                double fn = optDouble(c, CustomCrs.KEY_PROJ_FALSE_NORTHING, 0.0) * unitFactor;
                checkLatLon(lat0, lon0);
                // Lambert 1SP = 2SP with coincident parallels; k0 = 1 unless stated
                p = new LambertConformalConic2SP(0, ell, lat0, lat0, lat0, lon0, fe, fn,
                        k0 == null ? 1.0 : k0);
                break;
            }
            default:
                // phase 2.2: models outside the in-house set go through the
                // proj4j fallback, but only the verified subset (see there)
                if (Proj4Backend.supports(method)) {
                    p = Proj4Backend.create(c, method, ell, unitFactor);
                    break;
                }
                throw new IllegalArgumentException("unsupported projection model: "
                        + describeMethod(coordTrans, methodCode));
        }
        if (unitFactor == 1.0) {
            return p;
        }
        return new Scaled(p, unitFactor);
    }

    /** Reconciles the transformation type (3074) with the method code (3075). */
    private static int resolveMethod(Integer coordTrans, Integer methodCode) {
        if (coordTrans != null && coordTrans != CustomCrs.USER_DEFINED) {
            return coordTrans;
        }
        if (methodCode != null) {
            return methodCode;
        }
        throw new IllegalArgumentException("undeclared projection model (GeoKeys 3074/3075 missing)");
    }

    private static String describeMethod(Integer coordTrans, Integer methodCode) {
        return "3074=" + coordTrans + ", 3075=" + methodCode;
    }

    /**
     * Ellipsoid from the custom keys: 2057/2059 parameters take priority,
     * otherwise a GeoTIFF-table ellipsoid (2056) or datum (2050) code.
     */
    private static Ellipsoid ellipsoid(CustomCrs c) {
        Double a = c.dbl(CustomCrs.KEY_GEOG_SEMI_MAJOR);
        Double rf = c.dbl(CustomCrs.KEY_GEOG_INV_FLATTENING);
        if (a != null && rf != null) {
            if (!Double.isFinite(a) || !Double.isFinite(rf) || a <= 0 || rf < 0) {
                throw new IllegalArgumentException("invalid custom ellipsoid: a=" + a + ", 1/f=" + rf);
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
            throw new IllegalArgumentException("unknown GeoTIFF ellipsoid code: " + code);
        }
        throw new IllegalArgumentException("undefined ellipsoid (GeoKeys 2056/2057/2059 missing)");
    }

    private static double reqDouble(CustomCrs c, int id, int shownId) {
        Double v = c.dbl(id);
        if (v == null || !Double.isFinite(v)) {
            throw new IllegalArgumentException("GeoKey " + shownId + " (DOUBLE) missing or invalid");
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
                    "angular parameters out of range: lat=%s, lon=%s", lat, lon));
        }
    }

    /** Adapter that scales the source system's coordinates into metres. */
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
