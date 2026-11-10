package org.openstreetmap.josm.plugins.cog.reader;

/**
 * Conversion tuile web-mercatator <-> coordonnees projetees EPSG:3857.
 * (Grille spherique R=6378137 identique a OSM/JOSM/JMapViewer.)
 */
public final class MercatorTiles {

    /** Demi-circonference terrestre en metres (pi * 6378137). */
    public static final double HALF_CIRCUMFERENCE = 20037504.170531;
    public static final double MAX_MERC_LAT = 85.05112877980659;

    private MercatorTiles() {
    }

    public static double lonToX(double lon) {
        return lon * HALF_CIRCUMFERENCE / 180.0;
    }

    public static double latToY(double lat) {
        lat = Math.max(-MAX_MERC_LAT, Math.min(MAX_MERC_LAT, lat));
        double s = Math.sin(Math.toRadians(lat));
        // y = R * atanh(s) = (R/2) * ln((1+s)/(1-s))
        return HALF_CIRCUMFERENCE * (Math.log1p(s) - Math.log1p(-s)) / 2.0;
    }

    public static double xToLon(double x) {
        return x * 180.0 / HALF_CIRCUMFERENCE;
    }

    public static double yToLat(double y) {
        return Math.toDegrees(2 * Math.atan(Math.exp(y / HALF_CIRCUMFERENCE)) - Math.PI / 2);
    }

    /** Bbox 3857 d'une tuile de la grille mondiale OSM (tuiles carrees). */
    public static Bounds tileBounds(int zoom, int x, int y) {
        double n = Math.pow(2, zoom);
        double minX = (x / n) * 2 * HALF_CIRCUMFERENCE - HALF_CIRCUMFERENCE;
        double maxX = ((x + 1) / n) * 2 * HALF_CIRCUMFERENCE - HALF_CIRCUMFERENCE;
        // en mercator spherique, y = R*pi*(1 - 2*fraction) : lineaire en fraction de tuile
        double north = HALF_CIRCUMFERENCE * (1 - 2 * (y / n));
        double south = HALF_CIRCUMFERENCE * (1 - 2 * ((y + 1) / n));
        return new Bounds(minX, south, maxX, north);
    }
}
