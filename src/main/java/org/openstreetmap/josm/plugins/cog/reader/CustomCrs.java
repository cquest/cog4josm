package org.openstreetmap.josm.plugins.cog.reader;

/**
 * "user-defined" coordinate reference system of a GeoTIFF (GeoKeys codes
 * 32767): raw values of the GeoKeys and of the DOUBLE parameters of the
 * GeoDoubleParams tag (34736). No cartographic interpretation here: the
 * {@code crs} package turns these keys into a projection through
 * {@code Crs.fromCustom} (declarative mapping, no added formula).
 *
 * The expected values follow GeoTIFF 1.1 / libgeotiff, as written by
 * GDAL >= 3 (checked with exiftool and raw tag reads on reference COGs):
 * - custom projection: 3074 = 32767 and 3075 = projection method code;
 * - registered projection: 3074 = transformation type, 3075 = method code;
 * - linear quantities use the unit of GeoKey 3076, angles are in degrees.
 */
public final class CustomCrs {

    /** An entry of the GeoKeys directory (34735): id, locator, count, value. */
    public record Key(int id, int locator, int count, int value) {

        /** true if the key is present with an inline SHORT/LONG value. */
        public boolean inline() {
            return locator == 0;
        }

        /** true if the value is an index into the DOUBLE array (34736). */
        public boolean doubleRef() {
            return locator == TiffParser.TAG_GEO_DOUBLE_PARAMS;
        }
    }

    // TIFF tags
    public static final int GDT_DOUBLE = TiffParser.TAG_GEO_DOUBLE_PARAMS;   // 34736
    public static final int GDT_ASCII = TiffParser.TAG_GEO_ASCII_PARAMS;     // 34737

    // model / geographic GeoKeys
    public static final int KEY_MODEL_TYPE = 1024;
    public static final int KEY_GEOG_CODE = 2048;
    public static final int KEY_GEOG_AZIMUTH_UNITS = 2054;
    public static final int KEY_GEOG_DATUM = 2050;
    public static final int KEY_GEOG_ELLIPSOID = 2056;
    public static final int KEY_GEOG_SEMI_MAJOR = 2057;
    public static final int KEY_GEOG_INV_FLATTENING = 2059;
    public static final int KEY_GEOG_PRIME_MERIDIAN = 2061;

    // projected GeoKeys
    public static final int KEY_PROJ_CODE = 3072;
    public static final int KEY_PROJ_COORD_TRANS = 3074;
    public static final int KEY_PROJ_METHOD_CODE = 3075;
    public static final int KEY_PROJ_LINEAR_UNITS = 3076;
    public static final int KEY_PROJ_STD_PARALLEL1 = 3078;
    public static final int KEY_PROJ_STD_PARALLEL2 = 3079;
    public static final int KEY_PROJ_CENT_MERIDIAN = 3080;
    public static final int KEY_PROJ_ORIG_LAT = 3081;
    public static final int KEY_PROJ_FALSE_EASTING = 3082;
    public static final int KEY_PROJ_FALSE_NORTHING = 3083;
    public static final int KEY_PROJ_FALSE_ORIGIN_LONG = 3084;
    public static final int KEY_PROJ_FALSE_ORIGIN_LAT = 3085;
    public static final int KEY_PROJ_FALSE_ORIGIN_EASTING = 3086;
    public static final int KEY_PROJ_FALSE_ORIGIN_NORTHING = 3087;
    public static final int KEY_PROJ_SCALE_FACTOR = 3092;
    // Additional projected keys observed in GDAL custom mode (3074=32767,
    // phase 2.2): natural origin longitude/latitude (azimuthal and stereographic
    // methods), Hotine oblique Mercator centre scale/azimuth/rectified-grid
    // angle, and the polar-stereographic centre longitude.
    public static final int KEY_PROJ_NAT_ORIGIN_LONG = 3088;
    public static final int KEY_PROJ_NAT_ORIGIN_LAT = 3089;
    public static final int KEY_PROJ_SCALE_AT_CENTER = 3093;
    public static final int KEY_PROJ_AZIMUTH = 3094;
    public static final int KEY_PROJ_CENTER_LONG = 3095;
    public static final int KEY_PROJ_RECT_GRID_ANGLE = 3096;

    /** Geographic model type (GeoKey 1024 = 2). */
    public static final int MODEL_TYPE_GEOGRAPHIC = 2;
    /** "degree angular" angular unit (GeoKey 2054). */
    public static final int UNIT_DEGREE = 9102;
    /** GeoTIFF "User-Defined" value (the other keys define the CRS). */
    public static final int USER_DEFINED = 32767;

    // Coordinate transformation types / projection method codes.
    // GeoKey 3074 (libgeotiff table) and 3075 (codes written by GDAL when
    // 3074=32767) share the values 1 and 8; only 9/14 differ.
    public static final int CT_TRANSVERSE_MERCATOR = 1;
    public static final int CT_LAMBERT_CONFORMAL_2SP = 8;
    /** GeoKey 3074=9: "Mercator_Oblique" = Lambert conformal conic 1SP. */
    public static final int CT_LAMBERT_CONFORMAL_1SP = 9;
    /** GeoKey 3075=14: Lambert_Conformal_Conic_1SP (libgeotiff-era writers). */
    public static final int M_LAMBERT_CONFORMAL_1SP = 14;
    // Method codes GDAL writes in GeoKey 3075 when 3074=32767 (measured on
    // GDAL 3.x outputs, see AGENTS.md). Note the value collision: 14 means
    // Lambert 1SP for libgeotiff-style writers but oblique Stereographic for
    // GDAL (disambiguated by the presence of standard parallels 3078/3079).
    public static final int M_LAMBERT_AZIMUTHAL_EQUAL_AREA = 10;
    public static final int M_ALBERS_EQUAL_AREA = 11;
    public static final int M_EQUIDISTANT_CONIC = 13;
    public static final int M_STEREOGRAPHIC = 14;
    public static final int M_POLAR_STEREOGRAPHIC = 15;
    public static final int M_ORTHOGRAPHIC = 21;
    public static final int M_POLYCONIC = 22;
    public static final int M_CYLINDRICAL_EQUAL_AREA = 28;
    /** Hotine Oblique Mercator (EPSG method 9815), also used for somerc. */
    public static final int M_HOTINE_OBLIQUE_MERCATOR = 9815;

    /** GeoTIFF linear units -> metres (0 = unsupported code). */
    private static final double[] LINEAR_TO_METRE = new double[9110];
    static {
        LINEAR_TO_METRE[9001] = 1.0;                        // metre
        LINEAR_TO_METRE[9002] = 0.3048;                     // international foot
        LINEAR_TO_METRE[9003] = 1200.0 / 3937.0;            // US survey foot
        LINEAR_TO_METRE[9006] = 1200.0 / 3937.0 * 66.0;     // chain (Gunter, US survey)
        LINEAR_TO_METRE[9008] = 1000.0;                     // kilometre
        LINEAR_TO_METRE[9061] = 1000.0;                     // kilometre
        LINEAR_TO_METRE[9096] = 1200.0 / 3937.0;            // US survey foot
        LINEAR_TO_METRE[9109] = 1852.0;                     // international nautical mile
    }

    public final Key[] keys;
    /** DOUBLE array of tag 34736 (may be empty). */
    public final double[] doubleParams;
    /** ASCII citation of tag 34737 (may be null). */
    public final String citation;

    public CustomCrs(Key[] keys, double[] doubleParams, String citation) {
        this.keys = keys;
        this.doubleParams = doubleParams == null ? new double[0] : doubleParams;
        this.citation = citation;
    }

    /** Inline key (locator 0) with the given id, null when missing. */
    public Integer key(int id) {
        Key k = find(id);
        return k != null && k.inline() ? k.value() : null;
    }

    /** DOUBLE parameter referenced by the key (locator 34736), null when missing. */
    public Double dbl(int id) {
        Key k = find(id);
        if (k != null && k.doubleRef() && k.value() >= 0 && k.value() < doubleParams.length) {
            return doubleParams[k.value()];
        }
        return null;
    }

    public Key find(int id) {
        for (Key k : keys) {
            if (k.id() == id) {
                return k;
            }
        }
        return null;
    }

    /** Position of the 34737 citation carried by an ASCII key (locator 34737). */
    public boolean hasAscii(int id) {
        Key k = find(id);
        return k != null && k.locator() == GDT_ASCII;
    }

    /** Conversion factor from the declared linear units (3076) to metres, null for an unknown code. */
    public static Double linearToMetre(int code) {
        double f = code >= 0 && code < LINEAR_TO_METRE.length ? LINEAR_TO_METRE[code] : 0;
        return f == 0 ? null : f;
    }
}
