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
    private final double n, bigF, rho0, k0;

    public LambertConformalConic2SP(int epsg, Ellipsoid ell, double stdPar1Deg, double stdPar2Deg,
                                    double lat0Deg, double lon0Deg, double fe, double fn) {
        this(epsg, ell, stdPar1Deg, stdPar2Deg, lat0Deg, lon0Deg, fe, fn, null);
    }

    /**
     * @param k0AtLat0 facteur d'echelle a lat0 ; null = 1.0, convention GeoTIFF/PROJ
     * ou le facteur n'est porte que s'il est explicite (GeoKey 3092). Comme PROJ,
     * k0 s'applique a tout le rayon rho : le point (lat0, lon0) tombe exactement
     * sur (fe, fn) et la scale locale a lat0 vaut k0 (verifie contre GDAL).
     * Les deux paralleles d'entree/sortie peuvent etre egaux (Lambert 1SP).
     * Noms de parametres explicites ("Deg") : eviter toute confusion avec les
     * champs en radians (piege de portee dans les constructeurs, cf. AGENTS.md).
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
