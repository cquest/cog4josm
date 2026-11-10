package org.openstreetmap.josm.plugins.cog.crs;

/**
 * Projection cartographique plane (datum traite comme WGS84/ETRF : les
 * differences de datum RGF97/ETRS89 vs WGS84 sont negligees, < 1 m,
 * acceptable pour un fond cartographique).
 */
public interface Projection {

    /** Projette lat/lon (degres) vers les coordonnees planes {x, y} (metres). */
    double[] project(double lat, double lon);

    /** Inverse : coordonnees planes (metres) vers {lat, lon} (degres). */
    double[] unproject(double x, double y);

    /** Code EPSG de la projection. */
    int epsg();
}
