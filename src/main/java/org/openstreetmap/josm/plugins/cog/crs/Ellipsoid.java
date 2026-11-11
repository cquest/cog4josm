package org.openstreetmap.josm.plugins.cog.crs;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Reference ellipsoid (semi-major axis a, inverse flattening 1/f). */
public final class Ellipsoid {

    private static final Map<String, Ellipsoid> CACHE = new HashMap<>();

    public static final Ellipsoid WGS84 = register("WGS84", 6378137.0, 298.257223563);
    /** GRS80: identical to WGS84 to within a millimetre (RGF97 / ETRS89). */
    public static final Ellipsoid GRS80 = register("GRS80", 6378137.0, 298.257222101);

    /**
     * Named ellipsoids behind the GeoTIFF codes (GeogGeodeticDatum 6xxx and
     * GeogEllipsoid 7xxx, libgeotiff table). A code missing from this table is
     * rejected: the file must provide a and 1/f (GeoGeoSemanticSize 2057/2059).
     */
    private static final Map<Integer, double[]> GEOKEY_CODES = new HashMap<>();
    static {
        // datums 6xxx
        putCode(6001, 6378137.0, 298.257223563);  // WGS 84
        putCode(6326, 6378137.0, 298.257223563);  // World Geodetic System 84
        putCode(6255, 6378137.0, 298.257222101);  // World Geodetic System 84 (GRS80)
        putCode(6252, 6378137.0, 298.257222101);  // NAD83 (GRS80)
        putCode(6267, 6378137.0, 298.257222101);  // Informative Coord System 1985
        putCode(6152, 6378149.2, 293.4660212936269); // NAD27 (Clarke 1866)
        putCode(6230, 6378249.2, 293.4660212936269); // NTF (Clarke 1880 IGN)
        putCode(6201, 6378206.4, 294.9747461);   // ADG (Clarke 1858)
        putCode(6204, 6377397.155, 299.1528128); // AGD66 (Airy 1830)
        putCode(6207, 6378155.0, 298.2997104);   // Ain el Abd (Bessel mod.)
        putCode(6208, 6377301.27, 299.3249646);  // Aidart (Airy mod. 1830)
        putCode(6802, 6378206.4, 294.9747461);   // CAMERON Country (Clarke 1858)
        putCode(6809, 6378206.4, 294.9747461);   // Kampasasim (Clarke 1858)
        putCode(6811, 6378206.4, 294.9747461);   // Kertau (RSO) (Clarke 1858)
        putCode(6815, 6378206.4, 294.9747461);   // Port St. John (Clarke 1858)
        putCode(6816, 6378206.4, 294.9747461);   // Puerto Rico (Clarke 1858)
        putCode(6209, 6377340.189, 299.3249646); // Australian National (AUS)
        putCode(6211, 6377397.155, 299.1528128); // British 1931 (Airy mod.)
        putCode(6212, 6377397.155, 299.1528128); // British 1931 (Epilogue)
        putCode(6214, 6378388.0, 298.3);         // Budapest (International 1924)
        putCode(6249, 6378388.0, 298.3);         // Eucador (Quito) Intl 1924
        putCode(6250, 6378388.0, 298.3);         // Eucador (Quito) Guayaquil
        putCode(6701, 6378388.0, 298.3);         // Peru 3rd (Intl 1924)
        putCode(6702, 6378388.0, 298.3);         // Peru 5th (Intl 1924)
        putCode(6703, 6378388.0, 298.3);         // Peru 6th (Intl 1924)
        putCode(6807, 6378388.0, 298.3);         // Ganou (Intl 1924)
        putCode(6004, 6378388.0, 298.3);         // International 1924
        putCode(6006, 6378206.4, 294.9747461);   // Clarke 1858 (US)
        putCode(6007, 6378249.2, 293.4660212936269); // IGN / Clarke 1880 (France)
        putCode(6011, 6378137.0, 298.257223563); // NAVDEV 87
        putCode(6022, 6378137.0, 298.257223563); // WGS 72
        putCode(6008, 6378145.0, 298.25);        // TS Old Harness
        // ellipsoids 7xxx
        putCode(7030, 6378137.0, 298.257223563); // WGS 84
        putCode(7031, 6378137.0, 298.257223563); // WGS 84 (same as code 6326)
        putCode(7019, 6378137.0, 298.257222101); // GRS 1980
        putCode(7035, 6378137.0, 298.257222101); // GRS 1980 (same as code 6255)
        putCode(7004, 6378206.4, 294.9747461);   // Clarke 1866
        putCode(7015, 6378249.145, 293.4663);    // Clarke 1880 (variable)
        putCode(7006, 6378249.145, 293.4663);    // Clarke 1880 (Intl)
        putCode(7001, 6378249.2, 293.4660212936269); // Clarke 1880 (IGN)
        putCode(7007, 6378249.2, 293.4660212936269); // Clarke 1880 (IGN)
        putCode(7021, 6377397.155, 299.1528128); // Airy 1830
        putCode(7022, 6377298.556, 299.3249646); // Modified Airy
        putCode(7027, 6377301.27, 299.3249646);  // Airy Modified 1830
        putCode(7008, 6377340.189, 299.3249646); // Australian National Spheroid
        putCode(7036, 6377397.155, 299.1528128); // British 1931
        putCode(7005, 6378388.0, 298.3);         // International 1924 (Hayford)
        putCode(7020, 6378388.0, 298.3);         // International 1924
        putCode(7011, 6378166.0, 298.2996403);   // Bessel 1841 (Namibia)
        putCode(7012, 6376896.0, 299.32894);     // Bessel 1841 (Namibia)
        putCode(7014, 6377492.018, 299.1528128); // Bessel 1841 (Cape)
        putCode(7018, 6370997.0, 298.2997104);   // Clarke 1858 (Michigan)
        putCode(7017, 6370997.0, 298.2997104);   // Clarke 1858
        putCode(7013, 6378249.2, 293.4660212936269); // Clarke 1880 (IGN)
    }

    private static void putCode(int code, double a, double rf) {
        GEOKEY_CODES.put(code, new double[]{a, rf});
    }

    /** Ellipsoid for a GeoTIFF 6xxx/7xxx code, or null when the code is unknown. */
    public static synchronized Ellipsoid forGeoKeyCode(int code) {
        double[] p = GEOKEY_CODES.get(code);
        return p == null ? null : of(p[0], p[1]);
    }

    /**
     * Custom ellipsoid defined by its parameters (a in metres, 1/f).
     * A zero flattening (1/f = 0, perfect sphere) is accepted.
     */
    public static synchronized Ellipsoid of(double a, double invFlattening) {
        if (!(a > 0) || invFlattening < 0) {
            throw new IllegalArgumentException("invalid ellipsoid parameters: a="
                    + a + ", 1/f=" + invFlattening);
        }
        Ellipsoid e = CACHE.get(key(a, invFlattening));
        if (e == null) {
            e = new Ellipsoid("custom", a, invFlattening);
            CACHE.put(key(a, invFlattening), e);
        }
        return e;
    }

    private static Ellipsoid register(String name, double a, double rf) {
        Ellipsoid e = new Ellipsoid(name, a, rf);
        CACHE.put(key(a, rf), e);
        return e;
    }

    private static String key(double a, double rf) {
        return String.format(Locale.ROOT, "%.6f|%.9f", a, rf);
    }

    public final String name;
    public final double semiMajorAxis;
    public final double inverseFlattening;
    public final double flattening;
    /** Square of the first eccentricity e^2 = f(2-f). */
    public final double e2;
    /** Square of the second eccentricity e'^2 = e^2/(1-e^2). */
    public final double ep2;
    public final double e;

    private Ellipsoid(String name, double a, double rf) {
        this.name = name;
        this.semiMajorAxis = a;
        this.inverseFlattening = rf;
        this.flattening = rf == 0 ? 0 : 1.0 / rf;
        this.e2 = flattening * (2.0 - flattening);
        this.ep2 = e2 / (1.0 - e2);
        this.e = Math.sqrt(e2);
    }
}
