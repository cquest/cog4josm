package org.openstreetmap.josm.plugins.cog.reader;

import java.io.IOException;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import org.openstreetmap.josm.plugins.cog.crs.Projection;

/**
 * Recompose une tuile d'affichage (cible {@code targetSize} x {@code targetSize})
 * en choisissant le niveau de pyramide le plus adequat, en telechargeant via la
 * {@link CogReader} uniquement les tuiles sources intersectant la boite.
 */
public final class CogComposer {

    /** Limite de garde : evite de rassembler une fenetre source demesuree. */
    private static final int MAX_WINDOW_PX = 4096;

    /** Pas de la grille de controle du mode reprojection (G x G points exacts). */
    private static final int WARP_GRID = 9;

    private CogComposer() {
    }

    public static BufferedImage compose(CogReader reader, Bounds bbox, int targetSize) throws IOException {
        CogInfo ci = reader.info();
        if (bbox.isEmpty()) {
            return null;
        }
        if (!bbox.intersects(ci.extentMinX(), ci.extentMinY(), ci.extentMaxX(), ci.extentMaxY())) {
            return null;
        }
        double wantedRes = Math.max(bbox.width(), bbox.height()) / targetSize;
        int levelIndex = ci.bestLevelFor(wantedRes);
        return compose(reader, bbox, targetSize, levelIndex);
    }

    public static BufferedImage compose(CogReader reader, Bounds bbox, int targetSize, int levelIndex) throws IOException {
        CogInfo ci = reader.info();
        CogInfo.Level level = ci.level(levelIndex);
        double res = ci.resolution(levelIndex);

        int px0 = clampTop((int) Math.floor((bbox.minX() - ci.originX) / res), level.width);
        int px1 = clampTop((int) Math.ceil((bbox.maxX() - ci.originX) / res), level.width);
        int py0 = clampTop((int) Math.floor((ci.originY - bbox.maxY()) / res), level.height);
        int py1 = clampTop((int) Math.ceil((ci.originY - bbox.minY()) / res), level.height);
        if (px1 <= px0 || py1 <= py0) {
            return null;
        }
        int windowW = Math.min(px1 - px0, MAX_WINDOW_PX);
        int windowH = Math.min(py1 - py0, MAX_WINDOW_PX);

        BufferedImage window = buildWindow(reader, levelIndex, px0, py0, windowW, windowH);

        BufferedImage out = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(window, 0, 0, targetSize, targetSize, null);
        } finally {
            g2.dispose();
        }
        return out;
    }

    /**
     * Mode reprojection : la boite {@code bbox} est exprimee dans la grille
     * web-mercator d'affichage (EPSG:3857) ; le COG est dans un autre systeme
     * ({@code proj}). Une grille de controle {@value WARP_GRID} x {@value WARP_GRID}
     * est projetee exactement (3857 -> lat/lon -> coordonnées modeles -> pixels
     * sources) ; chaque pixel de sortie recoit des coordonnees sources interpolatees
     * bilineairement (approximation lineaire locale, reguliere a l'echelle d'une
     * tuile) puis est echantillonne en bilineaire ARGB dans la fenetre source.
     *
     * Le niveau de pyramide est choisi pour que la fenetre source projetee ait la
     * taille la plus proche de {@code targetSize} (rapport logarithmique).
     */
    public static BufferedImage composeWarp(CogReader reader, Projection proj, Bounds bbox, int targetSize)
            throws IOException {
        CogInfo ci = reader.info();
        if (bbox.isEmpty()) {
            return null;
        }
        int g = WARP_GRID;
        double[] mx = new double[g * g];
        double[] my = new double[g * g];
        double gMinX = Double.MAX_VALUE, gMaxX = -Double.MAX_VALUE;
        double gMinY = Double.MAX_VALUE, gMaxY = -Double.MAX_VALUE;
        for (int v = 0; v < g; v++) {
            // ligne 0 de la sortie = bord Nord de la boite (maxY) : le maillage
            // suit la meme orientation que l'image Java (ligne 0 en haut)
            double lat = MercatorTiles.yToLat(bbox.maxY() - (v / (double) (g - 1)) * bbox.height());
            for (int u = 0; u < g; u++) {
                double lon = MercatorTiles.xToLon(bbox.minX() + (u / (double) (g - 1)) * bbox.width());
                double[] m = proj.project(lat, lon);
                mx[v * g + u] = m[0];
                my[v * g + u] = m[1];
                gMinX = Math.min(gMinX, m[0]);
                gMaxX = Math.max(gMaxX, m[0]);
                gMinY = Math.min(gMinY, m[1]);
                gMaxY = Math.max(gMaxY, m[1]);
            }
        }
        if (gMaxX < ci.extentMinX() || gMinX > ci.extentMaxX()
                || gMaxY < ci.extentMinY() || gMinY > ci.extentMaxY()) {
            return null;
        }

        int best = -1;
        double bestErr = Double.MAX_VALUE;
        for (int li = 0; li < ci.levelCount(); li++) {
            double[] span = windowSpan(ci, li, gMinX, gMaxX, gMinY, gMaxY);
            double err = Math.abs(Math.log(Math.max(span[0], span[1]) / targetSize));
            // fenetre raisonnable exigee ; a defaut, le niveau le plus grossier
            // (les COG ont une pyramide complete, le dernier niveau est minuscule)
            if (span[0] > MAX_WINDOW_PX || span[1] > MAX_WINDOW_PX) {
                if (li == ci.levelCount() - 1) {
                    best = li;
                }
                continue;
            }
            if (err < bestErr) {
                bestErr = err;
                best = li;
            }
        }
        if (best < 0) {
            return null;
        }
        CogInfo.Level level = ci.level(best);
        double resX = ci.scaleX * ci.level(0).width / (double) level.width;
        double resY = ci.scaleY * ci.level(0).height / (double) level.height;

        double colMin = (gMinX - ci.originX) / resX;
        double colMax = (gMaxX - ci.originX) / resX;
        double rowMin = (ci.originY - gMaxY) / resY;
        double rowMax = (ci.originY - gMinY) / resY;
        int px0 = clampTop((int) Math.floor(colMin), level.width);
        int px1 = clampTop((int) Math.ceil(colMax), level.width);
        int py0 = clampTop((int) Math.floor(rowMin), level.height);
        int py1 = clampTop((int) Math.ceil(rowMax), level.height);
        if (px1 <= px0 || py1 <= py0) {
            return null;
        }
        int windowW = Math.min(px1 - px0, MAX_WINDOW_PX);
        int windowH = Math.min(py1 - py0, MAX_WINDOW_PX);
        BufferedImage window = buildWindow(reader, best, px0, py0, windowW, windowH);

        double[] colAt = new double[g * g];
        double[] rowAt = new double[g * g];
        for (int k = 0; k < g * g; k++) {
            colAt[k] = (mx[k] - ci.originX) / resX;
            rowAt[k] = (ci.originY - my[k]) / resY;
        }

        BufferedImage out = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
        for (int j = 0; j < targetSize; j++) {
            double fv = (j + 0.5) / targetSize * (g - 1);
            int v0 = Math.min((int) fv, g - 2);
            double dv = fv - v0;
            for (int i = 0; i < targetSize; i++) {
                double fu = (i + 0.5) / targetSize * (g - 1);
                int u0 = Math.min((int) fu, g - 2);
                double du = fu - u0;
                double col = bilerp(colAt, g, u0, v0, du, dv);
                double row = bilerp(rowAt, g, u0, v0, du, dv);
                out.setRGB(i, j, sampleBilinear(window, col - px0, row - py0));
            }
        }
        return out;
    }

    /** Etendue (largeur, hauteur en pixels) de la fenetre source au niveau donne. */
    private static double[] windowSpan(CogInfo ci, int levelIndex,
                                       double gMinX, double gMaxX, double gMinY, double gMaxY) {
        CogInfo.Level level = ci.level(levelIndex);
        double resX = ci.scaleX * ci.level(0).width / (double) level.width;
        double resY = ci.scaleY * ci.level(0).height / (double) level.height;
        return new double[]{(gMaxX - gMinX) / resX, (gMaxY - gMinY) / resY};
    }

    /** Assembles les tuiles TIFF couvrant la fenetre [px0,py0] + windowW/H. */
    private static BufferedImage buildWindow(CogReader reader, int levelIndex,
                                             int px0, int py0, int windowW, int windowH) throws IOException {
        CogInfo ci = reader.info();
        CogInfo.Level level = ci.level(levelIndex);
        BufferedImage window = new BufferedImage(windowW, windowH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = window.createGraphics();
        try {
            int tx0 = px0 / level.tileWidth;
            int tx1 = (px0 + windowW - 1) / level.tileWidth;
            int ty0 = py0 / level.tileHeight;
            int ty1 = (py0 + windowH - 1) / level.tileHeight;
            for (int ty = ty0; ty <= ty1; ty++) {
                for (int tx = tx0; tx <= tx1; tx++) {
                    if (!level.hasTile(tx, ty)) {
                        continue;
                    }
                    BufferedImage tile = reader.getTile(levelIndex, tx, ty);
                    g.drawImage(tile, tx * level.tileWidth - px0, ty * level.tileHeight - py0, null);
                }
            }
        } finally {
            g.dispose();
        }
        return window;
    }

    private static double bilerp(double[] a, int stride, int u0, int v0, double du, double dv) {
        double r0 = a[v0 * stride + u0] * (1 - du) + a[v0 * stride + u0 + 1] * du;
        double r1 = a[(v0 + 1) * stride + u0] * (1 - du) + a[(v0 + 1) * stride + u0 + 1] * du;
        return r0 * (1 - dv) + r1 * dv;
    }

    /**
     * Echantillonnage bilineaire ARGB conscient de l'alpha : les poids bilineaires
     * sont pondere par l'alpha des pixels voisins (le transparent ne "noircit" pas
     * le melange). Coordonnee continue : le centre du pixel k vaut k + 0.5.
     */
    private static int sampleBilinear(BufferedImage img, double wx, double wy) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (wx < 0.0 || wx > w || wy < 0.0 || wy > h) {
            return 0x00000000;
        }
        double fx = wx - 0.5;
        double fy = wy - 0.5;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        double tx = fx - x0;
        double ty = fy - y0;
        double w00 = (1 - tx) * (1 - ty);
        double w10 = tx * (1 - ty);
        double w01 = (1 - tx) * ty;
        double w11 = tx * ty;
        double sa = 0, sr = 0, sg = 0, sb = 0;
        for (int k = 0; k < 4; k++) {
            int x = x0 + (k & 1);
            int y = y0 + (k >> 1);
            if (x < 0 || x >= w || y < 0 || y >= h) {
                continue;
            }
            int p = img.getRGB(x, y);
            int a = (p >>> 24) & 0xFF;
            if (a == 0) {
                continue;
            }
            double weight;
            switch (k) {
                case 0: weight = w00; break;
                case 1: weight = w10; break;
                case 2: weight = w01; break;
                default: weight = w11; break;
            }
            double wa = weight * a;
            sa += wa;
            sr += wa * ((p >> 16) & 0xFF);
            sg += wa * ((p >> 8) & 0xFF);
            sb += wa * (p & 0xFF);
        }
        if (sa <= 0.0) {
            return 0x00000000;
        }
        int alpha = (int) Math.min(255, Math.round(sa));
        int r = (int) Math.round(sr / sa);
        int gg = (int) Math.round(sg / sa);
        int b = (int) Math.round(sb / sa);
        return (alpha << 24) | (r << 16) | (gg << 8) | b;
    }

    private static int clampTop(int v, int limit) {
        return Math.max(0, Math.min(v, limit));
    }
}
