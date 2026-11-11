package org.openstreetmap.josm.plugins.cog.reader;

/**
 * Web-mercator tile <-> EPSG:3857 projected coordinates conversion.
 * (Spherical grid R=6378137, identical to the OSM/JOSM/JMapViewer one.)
 */
public final class MercatorTiles {

    /** Radius of the WGS84 sphere (EPSG:3857), in metres. */
    public static final double EARTH_RADIUS = 6378137.0;
    /** Half the Earth circumference in metres (pi * 6378137 = 20037508.34...). */
    public static final double HALF_CIRCUMFERENCE = Math.PI * EARTH_RADIUS;
    public static final double MAX_MERC_LAT = 85.05112877980659;

    private MercatorTiles() {
    }

    public static double lonToX(double lon) {
        return lon * HALF_CIRCUMFERENCE / 180.0;
    }

    public static double latToY(double lat) {
        lat = Math.max(-MAX_MERC_LAT, Math.min(MAX_MERC_LAT, lat));
        double s = Math.sin(Math.toRadians(lat));
        // y = R * atanh(s), R = 6378137 = HALF_CIRCUMFERENCE / pi
        double r = HALF_CIRCUMFERENCE / Math.PI;
        return r * (Math.log1p(s) - Math.log1p(-s)) / 2.0;
    }

    public static double xToLon(double x) {
        return x * 180.0 / HALF_CIRCUMFERENCE;
    }

    public static double yToLat(double y) {
        // Inverse of latToY: lat = atan(sinh(y / R)) with R = HALF_CIRCUMFERENCE / pi
        double r = HALF_CIRCUMFERENCE / Math.PI;
        return Math.toDegrees(Math.atan(Math.sinh(y / r)));
    }

    /** 3857 bbox of a tile of the OSM world grid (square tiles). */
    public static Bounds tileBounds(int zoom, int x, int y) {
        double n = Math.pow(2, zoom);
        double minX = (x / n) * 2 * HALF_CIRCUMFERENCE - HALF_CIRCUMFERENCE;
        double maxX = ((x + 1) / n) * 2 * HALF_CIRCUMFERENCE - HALF_CIRCUMFERENCE;
        // in spherical mercator, y = R*pi*(1 - 2*fraction): linear in the tile fraction
        double north = HALF_CIRCUMFERENCE * (1 - 2 * (y / n));
        double south = HALF_CIRCUMFERENCE * (1 - 2 * ((y + 1) / n));
        return new Bounds(minX, south, maxX, north);
    }
}
