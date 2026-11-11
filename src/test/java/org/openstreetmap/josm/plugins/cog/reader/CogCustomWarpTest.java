package org.openstreetmap.josm.plugins.cog.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.cog.crs.Crs;
import org.openstreetmap.josm.plugins.cog.crs.Projection;

/**
 * End-to-end test of phase 2.1: COGs declared as "user-defined" GeoTIFF
 * systems (codes 32767, GeoKeys 34735/34736 as written by GDAL 3),
 * reprojected via the declarative mapping Crs.fromCustom.
 *
 * Anchors verified against GDAL (see CustomCrsTest):
 *   LCC custom  (700000, 6600000) == (3.0E, 46.5N)  (Lambert-93 parameters)
 *   TM custom   GDAL anchor (610974.823, 2216689.855) == (2.5E, 49.0N), Clarke 1880 (IGN)
 * The pixel placement check follows the method used in CogWarpTest.
 */
class CogCustomWarpTest {

    /** 64x48 px COG, 100 model-unit pixels, 2 levels, custom LCC GeoKeys. */
    private static CogReader openLccCog() throws IOException {
        double[] d = new double[9];
        int[] keys = TestTiffWriter.customKeysLcc(d);
        return CogReader.open(new InMemoryByteSource(TestTiffWriter.write(List.of(
                new TestTiffWriter.LevelSpec(64, 48, 8, 1, 8, false),
                new TestTiffWriter.LevelSpec(32, 24, 8, 1, 8, false)),
                32767, keys, d, 100.0, 700000.0, 6600000.0)));
    }

    /**
     * Custom TM COG Clarke 1880 (IGN): lat0 46.0, lon0 2.5, k0 1 (absent),
     * FE 600000, FN 2200000. GDAL anchor (verified with osgeo.osr):
     * (2.5E, 49.0N) -> (600000.000, 2533540.715), placed at row 24 / 48 of the COG.
     */
    private static CogReader openTmCog() throws IOException {
        double[] d = {46.0, 2.5, 600000.0, 2200000.0, 6378249.2, 293.4660212936269};
        int[] keys = {
                1, 1, 0, 13,
                1024, 0, 1, 1,
                2048, 0, 1, 32767,
                2056, 0, 1, 32767,
                2057, 34736, 1, 4,
                2059, 34736, 1, 5,
                3072, 0, 1, 32767,
                3074, 0, 1, 32767,
                3075, 0, 1, 1, // Transverse Mercator
                3076, 0, 1, 9001,
                3080, 34736, 1, 1, // lon0
                3081, 34736, 1, 0, // lat0
                3082, 34736, 1, 2, // false easting
                3083, 34736, 1, 3, // false northing
        };
        return CogReader.open(new InMemoryByteSource(TestTiffWriter.write(List.of(
                new TestTiffWriter.LevelSpec(64, 48, 8, 1, 8, false),
                new TestTiffWriter.LevelSpec(32, 24, 8, 1, 8, false)),
                32767, keys, d, 100.0, 600000.0, TM_ANCHOR_Y + 2400.0)));
    }

    /** Y ordinate (model units) of the GDAL anchor (2.5E, 49.0N). */
    private static final double TM_ANCHOR_Y = 2533540.7151;

    private static Bounds bbox3857(double minLon, double minLat, double maxLon, double maxLat, double margin) {
        double dx = (maxLon - minLon) * margin;
        double dy = (maxLat - minLat) * margin;
        return new Bounds(
                MercatorTiles.lonToX(minLon - dx), MercatorTiles.latToY(minLat - dy),
                MercatorTiles.lonToX(maxLon + dx), MercatorTiles.latToY(maxLat + dy));
    }

    /** Bilinear value of the pix() pattern at source coordinates (col, row). */
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

    /** Placement check identical to CogWarpTest (tolerance 0.15 source px). */
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
                assertTrue(((argb >>> 24) & 0xFF) > 250, "transparent pixel at (" + i + "," + j + ")");
                for (int ch = 0; ch < 3; ch++) {
                    double min = Double.MAX_VALUE;
                    double max = -Double.MAX_VALUE;
                    for (int dc = -1; dc <= 1; dc++) {
                        for (int dr = -1; dr <= 1; dr++) {
                            double v = motifBilinear(col - 0.5 + dc * 0.15, row - 0.5 + dr * 0.15)[ch];
                            min = Math.min(min, v);
                            max = Math.max(max, v);
                        }
                    }
                    int got = (argb >> (16 - 8 * ch)) & 0xFF;
                    assertTrue(got >= min - 2 && got <= max + 2,
                            "channel " + ch + " pixel (" + i + "," + j + ") source=(" + col + "," + row
                                    + ") value " + got + " outside [" + min + "," + max + "]");
                }
                checked++;
            }
        }
        assertTrue(checked >= 20, "too few points checked: " + checked);
    }

    @Test
    void customLccMatchesEpsg2154() throws IOException {
        try (CogReader r = openLccCog()) {
            CogInfo info = r.info();
            assertEquals(32767, info.crsCode);
            assertNotNull(info.customCrs);
            Projection custom = Crs.fromCustom(info.customCrs);
            Projection l93 = Crs.forEpsg(2154);
            // GDAL anchor: NW = (3.0E, 46.5N) exactly
            double[] nw = custom.unproject(700000.0, 6600000.0);
            assertEquals(46.5, nw[0], 1e-6);
            assertEquals(3.0, nw[1], 1e-6);
            double[] se = custom.unproject(706400.0, 6595200.0);
            Bounds bbox = bbox3857(Math.min(nw[1], se[1]), Math.min(nw[0], se[0]),
                    Math.max(nw[1], se[1]), Math.max(nw[0], se[0]), 0.02);
            BufferedImage img = CogComposer.composeWarp(r, custom, bbox, 256);
            assertNotNull(img);
            checkPlacement(img, bbox, custom, 700000.0, 6600000.0, 100.0, 6, 6, 64, 48);

            // warped with the registered 2154 projection: the renderings must match
            BufferedImage ref = CogComposer.composeWarp(r, l93, bbox, 256);
            assertNotNull(ref);
            int diff = 0;
            for (int j = 0; j < 256; j += 16) {
                for (int i = 0; i < 256; i += 16) {
                    if (ref.getRGB(i, j) != img.getRGB(i, j)) {
                        diff++;
                    }
                }
            }
            assertTrue(diff <= 1, "custom vs EPSG:2154 renderings diverge: " + diff + " px");
        }
    }

    @Test
    void customTransverseMercatorPlacement() throws IOException {
        try (CogReader r = openTmCog()) {
            Projection tm = Crs.fromCustom(r.info().customCrs);
            // model origin = COG NW corner, 24 px above the GDAL anchor
            double originX = 600000.0;
            double originY = TM_ANCHOR_Y + 2400.0;
            // GDAL anchor: (2.5E, 49.0N) -> (600000, 2533540.715) = row 24 of the COG
            double[] m = tm.project(49.0, 2.5);
            assertEquals(originX, m[0], 1e-3);
            assertEquals(TM_ANCHOR_Y, m[1], 1e-3);
            double[] nw = tm.unproject(originX, originY);
            double[] se = tm.unproject(originX + 6400.0, originY - 4800.0);
            Bounds bbox = bbox3857(Math.min(nw[1], se[1]), Math.min(nw[0], se[0]),
                    Math.max(nw[1], se[1]), Math.max(nw[0], se[0]), 0.02);
            BufferedImage img = CogComposer.composeWarp(r, tm, bbox, 256);
            assertNotNull(img);
            checkPlacement(img, bbox, tm, originX, originY, 100.0, 6, 6, 64, 48);
        }
    }
}
