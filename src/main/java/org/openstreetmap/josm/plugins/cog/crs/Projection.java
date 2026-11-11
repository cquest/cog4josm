package org.openstreetmap.josm.plugins.cog.crs;

/**
 * Plane map projection (the datum is handled as WGS84/ETRF: the datum
 * differences between RGF97/ETRS89 and WGS84 are neglected, < 1 m,
 * acceptable for a background map).
 */
public interface Projection {

    /** Projects lat/lon (degrees) into plane coordinates {x, y} (metres). */
    double[] project(double lat, double lon);

    /** Inverse: plane coordinates (metres) into {lat, lon} (degrees). */
    double[] unproject(double x, double y);

    /** EPSG code of the projection. */
    int epsg();
}
