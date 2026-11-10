package org.openstreetmap.josm.plugins.cog.crs;

/**
 * Projection transverse de Mercator ellipsoidale (Snyder, USGS PP 1312),
 * parametree pour couvrir les fuseaux UTM (k0=0.9996, lon0 = 6*z-183).
 * Series au 6e ordre : precision sub-millimetrique dans un fuseau UTM.
 */
public final class TransverseMercator implements Projection {

    private final int epsg;
    private final Ellipsoid ell;
    private final double k0, lat0, lon0, falseEasting, falseNorthing;
    private final double m0;

    public TransverseMercator(int epsg, Ellipsoid ell, double k0,
                              double lon0, double lat0, double falseEasting, double falseNorthing) {
        this.epsg = epsg;
        this.ell = ell;
        this.k0 = k0;
        this.lon0 = Math.toRadians(lon0);
        this.lat0 = Math.toRadians(lat0);
        this.falseEasting = falseEasting;
        this.falseNorthing = falseNorthing;
        this.m0 = meridianArc(this.lat0);
    }

    // Coefficients du developpement de l'arc de meridien (Snyder 3-21)
    private double meridianArc(double phi) {
        double e2 = ell.e2;
        double c0 = 1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256;
        double c1 = 3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2 * e2 * e2 / 1024;
        double c2 = 15 * e2 * e2 / 256 + 45 * e2 * e2 * e2 / 1024;
        double c3 = 35 * e2 * e2 * e2 / 3072;
        return ell.semiMajorAxis * (c0 * phi - c1 * Math.sin(2 * phi)
                + c2 * Math.sin(4 * phi) - c3 * Math.sin(6 * phi));
    }

    @Override
    public double[] project(double lat, double lon) {
        double phi = Math.toRadians(lat);
        double dLon = Math.toRadians(lon) - lon0;
        double sinPhi = Math.sin(phi);
        double cosPhi = Math.cos(phi);
        double tanPhi = Math.tan(phi);
        double e2 = ell.e2;
        double ep2 = ell.ep2;
        double nPrime = ell.semiMajorAxis / Math.sqrt(1 - e2 * sinPhi * sinPhi);
        double t = tanPhi * tanPhi;
        double c = ep2 * cosPhi * cosPhi;
        double a = dLon * cosPhi;
        double a2 = a * a;
        double m = meridianArc(phi);
        double x = falseEasting + k0 * nPrime * (a + (1 - t + c) * a * a2 / 6
                + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * a * a2 * a2 / 120);
        double y = falseNorthing + k0 * (m - m0 + nPrime * tanPhi * (a2 / 2
                + (5 - t + 9 * c + 4 * c * c) * a2 * a2 / 24
                + (61 - 58 * t + t * t + 600 * c - 330 * ep2) * a2 * a2 * a2 / 720));
        return new double[]{x, y};
    }

    @Override
    public double[] unproject(double x, double y) {
        double e2 = ell.e2;
        double ep2 = ell.ep2;
        double m = m0 + (y - falseNorthing) / k0;
        double mu = m / (ell.semiMajorAxis * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256));
        double e1 = (1 - Math.sqrt(1 - e2)) / (1 + Math.sqrt(1 - e2));
        double phi1 = mu + (3 * e1 / 2 - 27 * e1 * e1 * e1 / 32) * Math.sin(2 * mu)
                + (21 * e1 * e1 / 16 - 55 * e1 * e1 * e1 * e1 / 32) * Math.sin(4 * mu)
                + (151 * e1 * e1 * e1 / 96) * Math.sin(6 * mu)
                + (1097 * e1 * e1 * e1 * e1 / 512) * Math.sin(8 * mu);
        double sinPhi1 = Math.sin(phi1);
        double cosPhi1 = Math.cos(phi1);
        double tanPhi1 = Math.tan(phi1);
        double c1 = ep2 * cosPhi1 * cosPhi1;
        double t1 = tanPhi1 * tanPhi1;
        double nPrime1 = ell.semiMajorAxis / Math.sqrt(1 - e2 * sinPhi1 * sinPhi1);
        double r1 = ell.semiMajorAxis * (1 - e2) / Math.pow(1 - e2 * sinPhi1 * sinPhi1, 1.5);
        double d = (x - falseEasting) / (nPrime1 * k0);
        double d2 = d * d;
        double phi = phi1 - (nPrime1 * tanPhi1 / r1) * (d2 / 2
                - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d2 * d2 / 24
                + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d2 * d2 * d2 / 720);
        double lon = lon0 + (d - (1 + 2 * t1 + c1) * d * d2 / 6
                + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d * d2 * d2 / 120) / cosPhi1;
        return new double[]{Math.toDegrees(phi), Math.toDegrees(lon)};
    }

    @Override
    public int epsg() {
        return epsg;
    }
}
