package org.openstreetmap.josm.plugins.cog.crs;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registre des projections supportees par le plugin.
 *
 * Volumes pris en charge :
 * <ul>
 *   <li>EPSG:4326 (geographique WGS84) et EPSG:3857 (Web Mercator) ;</li>
 *   <li>UTM WGS84 : zones nord 32601-32660, zones sud 32701-32760 ;</li>
 *   <li>EPSG:2154 (RGF97 / Lambert-93, France metropolitaine).</li>
 * </ul>
 * Les datums non-WGS84 (NTF, SAD69...) sont traites comme WGS84 : erreur
 * metrologique de l'ordre de la centaine de metres, acceptable pour un fond
 * d'carte mais documentee.
 */
public final class Crs {

    private static final Map<Integer, Projection> CACHE = new LinkedHashMap<>();

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
}
