package org.openstreetmap.josm.plugins.cog.reader;

/**
 * Systeme de reference "user-defined" d'un GeoTIFF (codes GeoKeys 32767) :
 * valeur brute des GeoKeys et des parametres DOUBLE de la balise
 * GeoDoubleParams (34736). Aucune interpretation cartographique ici :
 * le paquetage {@code crs} convertit ces clefs en projection via
 * {@code Crs.fromCustom} (mapping declaratif, aucune formule ajoutee).
 *
 * Les valeurs attendues suivent GeoTIFF 1.1 / libgeotiff, telles que les
 * ecrit GDAL >= 3 (verifie avec exiftool et lecture brute des tags sur des
 * COG de reference) :
 * - projection custom : 3074 = 32767 et 3075 = code de methode de projection ;
 * - projection enregistree : 3074 = type de transformation, 3075 = code methode ;
 * - les Grandeurs lineaires sont dans l'unite du GeoKey 3076, les angles en degres.
 */
public final class CustomCrs {

    /** Une entree du repertoire GeoKeys (34735) : id, locator, nombre, valeur. */
    public record Key(int id, int locator, int count, int value) {

        /** true si la clef est presente avec une valeur SHORT/LONG inline. */
        public boolean inline() {
            return locator == 0;
        }

        /** true si la valeur est un index dans le tableau DOUBLE (34736). */
        public boolean doubleRef() {
            return locator == TiffParser.TAG_GEO_DOUBLE_PARAMS;
        }
    }

    // balises TIFF
    public static final int GDT_DOUBLE = TiffParser.TAG_GEO_DOUBLE_PARAMS;   // 34736
    public static final int GDT_ASCII = TiffParser.TAG_GEO_ASCII_PARAMS;     // 34737

    // GeoKeys modele / geodetiques
    public static final int KEY_MODEL_TYPE = 1024;
    public static final int KEY_GEOG_CODE = 2048;
    public static final int KEY_GEOG_AZIMUTH_UNITS = 2054;
    public static final int KEY_GEOG_DATUM = 2050;
    public static final int KEY_GEOG_ELLIPSOID = 2056;
    public static final int KEY_GEOG_SEMI_MAJOR = 2057;
    public static final int KEY_GEOG_INV_FLATTENING = 2059;
    public static final int KEY_GEOG_PRIME_MERIDIAN = 2061;

    // GeoKeys projetes
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

    /** Modele de type geographique (GeoKey 1024 = 2). */
    public static final int MODEL_TYPE_GEOGRAPHIC = 2;
    /** Unite angulaire "degree angular" (GeoKey 2054). */
    public static final int UNIT_DEGREE = 9102;
    /** Valeur GeoTIFF "User-Defined" (les autres clefs definissent le systeme). */
    public static final int USER_DEFINED = 32767;

    // Types de transformation de coordonnees / codes de methode de projection.
    // GeoKey 3074 (table libgeotiff) et 3075 (codes ecrits par GDAL quand
    // 3074=32767) partagent les valeurs 1 et 8 ; seuls 9/14 different.
    public static final int CT_TRANSVERSE_MERCATOR = 1;
    public static final int CT_LAMBERT_CONFORMAL_2SP = 8;
    /** GeoKey 3074=9 : "Mercator_Oblique" = conique conforme Lambert 1SP. */
    public static final int CT_LAMBERT_CONFORMAL_1SP = 9;
    /** GeoKey 3075=14 : Lambert_Conformal_Conic_1SP. */
    public static final int M_LAMBERT_CONFORMAL_1SP = 14;

    /** Unites lineaires GeoTIFF -> metres (0 = code non supporte). */
    private static final double[] LINEAR_TO_METRE = new double[9110];
    static {
        LINEAR_TO_METRE[9001] = 1.0;                        // metre
        LINEAR_TO_METRE[9002] = 0.3048;                     // pied international
        LINEAR_TO_METRE[9003] = 1200.0 / 3937.0;            // pied US survey
        LINEAR_TO_METRE[9006] = 1200.0 / 3937.0 * 66.0;     // chaine (Gunter, US survey)
        LINEAR_TO_METRE[9008] = 1000.0;                     // kilometre
        LINEAR_TO_METRE[9061] = 1000.0;                     // kilometre
        LINEAR_TO_METRE[9096] = 1200.0 / 3937.0;            // pied US survey
        LINEAR_TO_METRE[9109] = 1852.0;                     // mille marin international
    }

    public final Key[] keys;
    /** Tableau DOUBLE de la balise 34736 (peut etre vide). */
    public final double[] doubleParams;
    /** Citation ASCII de la balise 34737 (peut etre null). */
    public final String citation;

    public CustomCrs(Key[] keys, double[] doubleParams, String citation) {
        this.keys = keys;
        this.doubleParams = doubleParams == null ? new double[0] : doubleParams;
        this.citation = citation;
    }

    /** Clef inline (locator 0) du code donne, null si absente. */
    public Integer key(int id) {
        Key k = find(id);
        return k != null && k.inline() ? k.value() : null;
    }

    /** Parametre DOUBLE reference par la clef (locator 34736), null si absent. */
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

    /** Emplacement de la citation 34737 portee par une clef ASCII (locator 34737). */
    public boolean hasAscii(int id) {
        Key k = find(id);
        return k != null && k.locator() == GDT_ASCII;
    }

    /** Facteur de conversion des unites lineaires declarees (3076) vers les metres, null si code inconnu. */
    public static Double linearToMetre(int code) {
        double f = code >= 0 && code < LINEAR_TO_METRE.length ? LINEAR_TO_METRE[code] : 0;
        return f == 0 ? null : f;
    }
}
