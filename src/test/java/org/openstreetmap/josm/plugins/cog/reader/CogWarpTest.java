package org.openstreetmap.josm.plugins.cog.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.cog.crs.Crs;
import org.openstreetmap.josm.plugins.cog.crs.Projection;

/**
 * Test du mode reprojection (composeWarp) avec des COG de test declares en
 * Lambert-93 (EPSG:2154) et UTM 31N (EPSG:32631). Les COG sont places sur des
 * ancres verifiees contre GDAL (voir ProjectionTest) :
 *   Lambert-93 (700000, 6600000) == (3.0E, 46.5N)
 *   UTM 31N    (500000, 5316300.224) == (3.0E, 48.0N)
 * Le motif de pixels etant deterministe (TestTiffWriter.pix), on verifie que
 * chaque pixel d'essai provient bien des coordonnees sources attendues, a
 * l'approximation d'interpolation du maillage pres (tolerance 0.15 px source).
 */
class CogWarpTest {

    /** COG 64x48 px, pixel de 100 unites-modele, 2 niveaux. */
    private static CogReader openCog(int crs, double originX, double originY) throws IOException {
        return CogReader.open(new InMemoryByteSource(TestTiffWriter.write(List.of(
                new TestTiffWriter.LevelSpec(64, 48, 8, 1, 8, false),
                new TestTiffWriter.LevelSpec(32, 24, 8, 1, 8, false)),
                crs, 100.0, originX, originY)));
    }

    /** Bbox 3857 d'un rectangle lat/lon, avec marge fractionnaire. */
    private static Bounds bbox3857(double minLon, double minLat, double maxLon, double maxLat, double margin) {
        double dx = (maxLon - minLon) * margin;
        double dy = (maxLat - minLat) * margin;
        return new Bounds(
                MercatorTiles.lonToX(minLon - dx), MercatorTiles.latToY(minLat - dy),
                MercatorTiles.lonToX(maxLon + dx), MercatorTiles.latToY(maxLat + dy));
    }

    /** Valeur bilineaire du motif pix() aux coordonnees sources (col, row). */
    private static double[] motifBilinear(double col, double row) {
        int c0 = (int) Math.floor(col);
        int r0 = (int) Math.floor(row);
        double tc = col - c0;
        double tr = row - r0;
        double[] out = new double[3];
        for (int ch = 0; ch < 3; ch++) {
            double v00 = TestTiffWriter.pix(c0, r0, ch, 8);
            double v10 = TestTiffWriter.pix(c0 + 1, r0, ch, 8);
            double v01 = TestTiffWriter.pix(c0, r0 + 1, ch, 8);
            double v11 = TestTiffWriter.pix(c0 + 1, r0 + 1, ch, 8);
            out[ch] = (v00 * (1 - tc) + v10 * tc) * (1 - tr) + (v01 * (1 - tc) + v11 * tc) * tr;
        }
        return out;
    }

    /**
     * Echantillonne la tuile reprojete : pour chaque point d'essai, calcule les
     * coordonnees sources attendues par la formule exacte et verifie que la
     * valeur du pixel sorti est encadree par le motif aux coordonnees attendues
     * +/- 0.15 px source (couvre l'erreur d'interpolation du maillage).
     */
    private static void checkPlacement(BufferedImage img, Bounds bbox, Projection proj,
                                       double originX, double originY, double res,
                                       double colMargin, double rowMargin, int w, int h) {
        int target = img.getWidth();
        int checked = 0;
        for (int j = 8; j < target - 8; j += 17) {
            for (int i = 8; i < target - 8; i += 19) {
                double x3857 = bbox.minX() + (i + 0.5) / target * bbox.width();
                double y3857 = bbox.maxY() - (j + 0.5) / target * bbox.height();
                double lat = MercatorTiles.yToLat(y3857);
                double lon = MercatorTiles.xToLon(x3857);
                double[] m = proj.project(lat, lon);
                double col = (m[0] - originX) / res;
                double row = (originY - m[1]) / res;
                if (col < colMargin || col > w - colMargin || row < rowMargin || row > h - rowMargin) {
                    continue;
                }
                int argb = img.getRGB(i, j);
                assertTrue(((argb >>> 24) & 0xFF) > 250, "pixel transparent en (" + i + "," + j + ")");
                for (int ch = 0; ch < 3; ch++) {
                    double min = Double.MAX_VALUE;
                    double max = -Double.MAX_VALUE;
                    for (int dc = -1; dc <= 1; dc++) {
                        for (int dr = -1; dr <= 1; dr++) {
                            // convention GeoTIFF : le centre du pixel k est en k+0.5,
                            // le motif est donc evalue en coordonnees "centre entier"
                            double v = motifBilinear(col - 0.5 + dc * 0.15, row - 0.5 + dr * 0.15)[ch];
                            min = Math.min(min, v);
                            max = Math.max(max, v);
                        }
                    }
                    int got = (argb >> (16 - 8 * ch)) & 0xFF;
                    assertTrue(got >= min - 2 && got <= max + 2,
                            "canal " + ch + " pixel (" + i + "," + j + ") source=(" + col + "," + row
                                    + ") valeur " + got + " hors [" + min + "," + max + "]");
                }
                checked++;
            }
        }
        assertTrue(checked >= 20, "trop peu de points verifies : " + checked);
    }

    @Test
    void lambert93Placement() throws IOException {
        Projection l93 = Crs.forEpsg(2154);
        try (CogReader r = openCog(2154, 700000.0, 6600000.0)) {
            // ancre GDAL : NW du COG = (3.0E, 46.5N) exactement
            double[] nw = l93.unproject(700000.0, 6600000.0);
            double[] se = l93.unproject(706400.0, 6595200.0);
            assertEquals(46.5, nw[0], 1e-6);
            assertEquals(3.0, nw[1], 1e-6);
            Bounds bbox = bbox3857(Math.min(nw[1], se[1]), Math.min(nw[0], se[0]),
                    Math.max(nw[1], se[1]), Math.max(nw[0], se[0]), 0.02);
            BufferedImage img = CogComposer.composeWarp(r, l93, bbox, 256);
            assertNotNull(img);
            assertEquals(256, img.getWidth());
            checkPlacement(img, bbox, l93, 700000.0, 6600000.0, 100.0, 6, 6, 64, 48);
        }
    }

    @Test
    void utm31nPlacement() throws IOException {
        Projection utm = Crs.forEpsg(32631);
        try (CogReader r = openCog(32631, 450000.0, 5415000.0)) {
            double[] nw = utm.unproject(450000.0, 5415000.0);
            double[] se = utm.unproject(456400.0, 5410200.0);
            Bounds bbox = bbox3857(Math.min(nw[1], se[1]), Math.min(nw[0], se[0]),
                    Math.max(nw[1], se[1]), Math.max(nw[0], se[0]), 0.02);
            BufferedImage img = CogComposer.composeWarp(r, utm, bbox, 256);
            assertNotNull(img);
            checkPlacement(img, bbox, utm, 450000.0, 5415000.0, 100.0, 6, 6, 64, 48);
        }
    }

    @Test
    void nullFarOutsideExtent() throws IOException {
        Projection l93 = Crs.forEpsg(2154);
        try (CogReader r = openCog(2154, 700000.0, 6600000.0)) {
            // tuile web loin de l'emprise (pole sud)
            Bounds bbox = MercatorTiles.tileBounds(5, 0, 0);
            assertNull(CogComposer.composeWarp(r, l93, bbox, 256));
        }
    }
}
