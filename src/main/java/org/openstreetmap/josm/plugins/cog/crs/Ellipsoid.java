package org.openstreetmap.josm.plugins.cog.crs;

/** Ellipsoide de reference (demi-grand axe a, aplatissement inverse 1/f). */
public final class Ellipsoid {

    public static final Ellipsoid WGS84 = new Ellipsoid("WGS84", 6378137.0, 298.257223563);
    /** GRS80 : identique a WGS84 au millimetre pres (RGF97 / ETRS89). */
    public static final Ellipsoid GRS80 = new Ellipsoid("GRS80", 6378137.0, 298.257222101);

    public final String name;
    public final double semiMajorAxis;
    public final double inverseFlattening;
    public final double flattening;
    /** Carre de la premiere excentricite e^2 = f(2-f). */
    public final double e2;
    /** Carre de la seconde excentricite e'^2 = e^2/(1-e^2). */
    public final double ep2;
    public final double e;

    private Ellipsoid(String name, double a, double rf) {
        this.name = name;
        this.semiMajorAxis = a;
        this.inverseFlattening = rf;
        this.flattening = 1.0 / rf;
        this.e2 = flattening * (2.0 - flattening);
        this.ep2 = e2 / (1.0 - e2);
        this.e = Math.sqrt(e2);
    }
}
