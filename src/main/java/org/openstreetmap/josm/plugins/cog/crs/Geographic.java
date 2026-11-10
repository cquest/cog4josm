package org.openstreetmap.josm.plugins.cog.crs;

/**
 * Coordonnees geographiques (EPSG:4326 ou systeme custom declare en GeoKeys) :
 * x = longitude, y = latitude, en degres. Les unites du modele sont donc les
 * degres. Un meridien principal non nul (GeoKey GeogPrimeMeridian 2061) est
 * pris en compte : x = longitude - meridien (longitude geodetique).
 */
public final class Geographic implements Projection {

    private final int epsg;
    private final double primeMeridian;

    public Geographic(int epsg) {
        this(epsg, 0.0);
    }

    public Geographic(int epsg, double primeMeridianDeg) {
        this.epsg = epsg;
        this.primeMeridian = primeMeridianDeg;
    }

    @Override
    public double[] project(double lat, double lon) {
        return new double[]{lon - primeMeridian, lat};
    }

    @Override
    public double[] unproject(double x, double y) {
        return new double[]{y, x + primeMeridian};
    }

    @Override
    public int epsg() {
        return epsg;
    }

    /** Vrai si le meridien principal est Greenwich (voie lineaire possible). */
    public boolean isPrimeMeridianZero() {
        return primeMeridian == 0.0;
    }
}
