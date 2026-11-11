package org.openstreetmap.josm.plugins.cog.crs;

/**
 * Geographic coordinates (EPSG:4326 or a custom system declared through
 * GeoKeys): x = longitude, y = latitude, in degrees. The model units are
 * therefore degrees. A non-zero principal meridian (GeoKey GeogPrimeMeridian
 * 2061) is taken into account: x = longitude - meridian (geodetic longitude).
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

    /** True when the principal meridian is Greenwich (linear path possible). */
    public boolean isPrimeMeridianZero() {
        return primeMeridian == 0.0;
    }
}
