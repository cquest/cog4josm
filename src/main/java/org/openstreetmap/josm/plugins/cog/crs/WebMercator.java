package org.openstreetmap.josm.plugins.cog.crs;

/**
 * Web Mercator (EPSG:3857): sphere with radius a = 6378137 m, formula
 * y = a * ln(tan(pi/4 + phi/2)). This is the projection used by OSM tiles.
 */
public final class WebMercator implements Projection {

    private static final double R = 6378137.0;

    public WebMercator() {
    }

    @Override
    public double[] project(double lat, double lon) {
        double phi = Math.toRadians(lat);
        // usual Web Mercator limits (lat ~ +-85.051129)
        double y = R * Math.log(Math.tan(Math.PI / 4 + phi / 2));
        return new double[]{Math.toRadians(lon) * R, y};
    }

    @Override
    public double[] unproject(double x, double y) {
        double lat = Math.toDegrees(2 * Math.atan(Math.exp(y / R)) - Math.PI / 2);
        double lon = Math.toDegrees(x / R);
        return new double[]{lat, lon};
    }

    @Override
    public int epsg() {
        return 3857;
    }
}
