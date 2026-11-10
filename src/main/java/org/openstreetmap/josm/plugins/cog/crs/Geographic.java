package org.openstreetmap.josm.plugins.cog.crs;

/**
 * Coordonnees geographiques WGS84 (EPSG:4326) : x = longitude, y = latitude,
 * en degres. Utilise pour les COG declares en 4326 (les unites du modele sont
 * alors les degres).
 */
public final class Geographic implements Projection {

    private final int epsg;

    public Geographic(int epsg) {
        this.epsg = epsg;
    }

    @Override
    public double[] project(double lat, double lon) {
        return new double[]{lon, lat};
    }

    @Override
    public double[] unproject(double x, double y) {
        return new double[]{y, x};
    }

    @Override
    public int epsg() {
        return epsg;
    }
}
