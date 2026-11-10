package org.openstreetmap.josm.plugins.cog.crs;

/**
 * Projection conique conforme de Lambert a deux paralleles d'entree/sortie
 * (EPSG:2154 Lambert-93...). Formules ellipsoidales de Snyder (USGS PP 1312).
 * L'iteration d'inversion de phi converge en 3-4 tours a la precision du mm.
 */
public final class LambertConformalConic2SP implements Projection {

    private final int epsg;
    private final Ellipsoid ell;
    private final double lat1, lat2, lat0, lon0, falseEasting, falseNorthing;
    private final double n, bigF, rho0;

    public LambertConformalConic2SP(int epsg, Ellipsoid ell, double stdPar1, double stdPar2,
                                    double lat0, double lon0, double falseEasting, double falseNorthing) {
        this.epsg = epsg;
        this.ell = ell;
        this.lat1 = Math.toRadians(stdPar1);
        this.lat2 = Math.toRadians(stdPar2);
        this.lat0 = Math.toRadians(lat0);
        this.lon0 = Math.toRadians(lon0);
        this.falseEasting = falseEasting;
        this.falseNorthing = falseNorthing;
        double m1 = m(lat1);
        double m2 = m(lat2);
        double t1 = t(lat1);
        double t2 = t(lat2);
        if (Math.abs(lat1 - lat2) < 1e-12) {
            this.n = Math.sin(lat1);
        } else {
            this.n = Math.log(m1 / m2) / Math.log(t1 / t2);
        }
        this.bigF = m1 / (n * Math.pow(t1, n));
        this.rho0 = ell.semiMajorAxis * bigF * Math.pow(t(this.lat0), n);
    }

    private double m(double phi) {
        double s = Math.sin(phi);
        return Math.cos(phi) / Math.sqrt(1 - ell.e2 * s * s);
    }

    private double t(double phi) {
        double e = ell.e;
        double s = Math.sin(phi);
        return Math.tan(Math.PI / 4 - phi / 2)
                / Math.pow((1 - e * s) / (1 + e * s), e / 2);
    }

    @Override
    public double[] project(double lat, double lon) {
        double phi = Math.toRadians(lat);
        double theta = n * (Math.toRadians(lon) - lon0);
        double rho = ell.semiMajorAxis * bigF * Math.pow(t(phi), n);
        double x = falseEasting + rho * Math.sin(theta);
        double y = falseNorthing + rho0 - rho * Math.cos(theta);
        return new double[]{x, y};
    }

    @Override
    public double[] unproject(double x, double y) {
        double dx = x - falseEasting;
        double dy = rho0 - (y - falseNorthing);
        double rho = Math.copySign(Math.hypot(dx, dy), n);
        double theta = Math.atan2(Math.copySign(1, n) * dx, dy);
        double tp = Math.pow(rho / (ell.semiMajorAxis * bigF), 1 / n);
        // inversion de t(phi) : point fixe de Snyder, convergence rapide
        double phi = Math.PI / 2 - 2 * Math.atan(tp);
        double e = ell.e;
        for (int i = 0; i < 12; i++) {
            double s = Math.sin(phi);
            double next = Math.PI / 2 - 2 * Math.atan(tp * Math.pow((1 - e * s) / (1 + e * s), e / 2));
            if (Math.abs(next - phi) < 1e-13) {
                phi = next;
                break;
            }
            phi = next;
        }
        double lon = lon0 + theta / n;
        return new double[]{Math.toDegrees(phi), Math.toDegrees(lon)};
    }

    @Override
    public int epsg() {
        return epsg;
    }
}
