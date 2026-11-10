package org.openstreetmap.josm.plugins.cog.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.List;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

class CogComposerTest {

    /** COG 64x48 px, pixel de 10 m, coin NW a (500000, 6250000), EPSG:3857. */
    private static CogReader openCog() throws IOException {
        byte[] tiff = TestTiffWriter.write(List.of(
                new TestTiffWriter.LevelSpec(64, 48, 8, 1, 8, false),
                new TestTiffWriter.LevelSpec(32, 24, 8, 1, 8, false)),
                3857, 10.0, 500000.0, 6250000.0);
        return CogReader.open(new InMemoryByteSource(tiff));
    }

    @Test
    void composesFullResOneToOne() throws IOException {
        try (CogReader r = openCog()) {
            // bbox 480x480 m au niveau 0 (res 10) => fenetre 48x48 px -> cible 48 px : 1:1
            Bounds bbox = new Bounds(500160, 6249520, 500640, 6250000);
            BufferedImage img = CogComposer.compose(r, bbox, 48);
            assertNotNull(img);
            assertEquals(48, img.getWidth());
            assertEquals(48, img.getHeight());
            for (int y = 0; y < 48; y += 7) {
                for (int x = 0; x < 48; x += 5) {
                    int argb = img.getRGB(x, y);
                    int gx = 16 + x;
                    int gy = y;
                    assertEquals(TestTiffWriter.pix(gx, gy, 0, 8), (argb >> 16) & 0xFF, "R x=" + x + " y=" + y);
                    assertEquals(TestTiffWriter.pix(gx, gy, 1, 8), (argb >> 8) & 0xFF, "G x=" + x + " y=" + y);
                    assertEquals(TestTiffWriter.pix(gx, gy, 2, 8), argb & 0xFF, "B x=" + x + " y=" + y);
                }
            }
        }
    }

    @Test
    void picksOverviewLevel() throws IOException {
        try (CogReader r = openCog()) {
            // bbox 320x320 m, cible 16 px => resolution voulue 20 = niveau 1 exactement
            Bounds bbox = new Bounds(500000, 6249680, 500320, 6250000);
            BufferedImage img = CogComposer.compose(r, bbox, 16);
            assertNotNull(img);
            for (int y = 0; y < 16; y += 3) {
                for (int x = 0; x < 16; x += 5) {
                    int argb = img.getRGB(x, y);
                    assertEquals(TestTiffWriter.pix(x, y, 0, 8), (argb >> 16) & 0xFF, "R(" + x + "," + y + ")");
                    assertEquals(TestTiffWriter.pix(x, y, 1, 8), (argb >> 8) & 0xFF);
                    assertEquals(TestTiffWriter.pix(x, y, 2, 8), argb & 0xFF);
                }
            }
        }
    }

    @Test
    void returnsNullOutsideExtent() throws IOException {
        try (CogReader r = openCog()) {
            assertNull(CogComposer.compose(r, new Bounds(0, 0, 100, 100), 256));
        }
    }
}
