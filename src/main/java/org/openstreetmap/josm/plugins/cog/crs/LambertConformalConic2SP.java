package org.openstreetmap.josm.plugins.cog.crs;

/**
 * Lambert conformal conic projection with two standard parallels
 * (EPSG:2154 Lambert-93...). Snyder ellipsoidal formulas (USGS PP 1312).
 * The iteration inverting phi converges in 3-4 rounds to millimetre accuracy.
 */
public final class LambertConformalConic2SP implements Projection {

    private final int epsg;
    private final Ellipsoid ell;
    private final double lat1, lat2, lat0, lon0, falseEasting, falseNorthing;
    private final double n, bigF, rho0, k0;

    public LambertConformalConic2SP(int epsg, Ellipsoid ell, double stdPar1Deg, double stdPar2Deg,
                                    double lat0Deg, double lon0Deg, double fe, double fn) {
        this(epsg, ell, stdPar1Deg, stdPar2Deg, lat0Deg, lon0Deg, fe, fn, null);
    }

    /**
     * @param k0AtLat0 scale factor at lat0; null = 1.0, the GeoTIFF/PROJ convention
     * where the factor is only carried when explicit (GeoKey 3092). As in PROJ,
     * k0 applies to the whole rho radius: the point (lat0, lon0) lands exactly
     * on (fe, fn) and the local scale at lat0 is k0 (verified against GDAL).
     * The two standard parallels may be equal (Lambert 1SP).
     * Explicit parameter names ("Deg"): avoid any confusion with the radian
     * fields (constructor scope pitfall, see AGENTS.md).
     */
    public LambertConformalConic2SP(int epsg, Ellipsoid ell, double stdPar1Deg, double stdPar2Deg,
                                    double lat0Deg, double lon0Deg, double fe, double fn,
                                    Double k0AtLat0) {
        this.epsg = epsg;
        this.ell = ell;
        this.lat1 = Math.toRadians(stdPar1Deg);
        this.lat2 = Math.toRadians(stdPar2Deg);
        this.lat0 = Math.toRadians(lat0Deg);
        this.lon0 = Math.toRadians(lon0Deg);
        this.falseEasting = fe;
        this.falseNorthing = fn;
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
        this.k0 = k0AtLat0 != null ? k0AtLat0 : 1.0;
        this.rho0 = ell.semiMajorAxis * bigF * Math.pow(t(lat0), n) * k0;
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
        double rho = ell.semiMajorAxis * bigF * Math.pow(t(phi), n) * k0;
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
        double tp = Math.pow(rho / (k0 * ell.semiMajorAxis * bigF), 1 / n);
        // inversion of t(phi): Snyder fixed-point iteration, fast convergence
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
