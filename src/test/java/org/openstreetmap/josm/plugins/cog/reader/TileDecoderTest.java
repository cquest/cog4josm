package org.openstreetmap.josm.plugins.cog.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

class TileDecoderTest {

    private static CogReader openOneLevel(TestTiffWriter.LevelSpec spec) throws IOException {
        byte[] tiff = TestTiffWriter.write(List.of(spec), 3857, 1.0, 0.0, 0.0);
        return CogReader.open(new InMemoryByteSource(tiff));
    }

    private static void checkRgb(CogReader r, int bits) throws IOException {
        BufferedImage img = r.getTile(0, 1, 1);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int gx = 16 + x;
                int gy = 16 + y;
                int argb = img.getRGB(x, y);
                int mask = (1 << bits) - 1;
                int r0 = (argb >> 16) & mask;
                int g0 = (argb >> 8) & mask;
                int b0 = argb & mask;
                assertEquals(TestTiffWriter.pix(gx, gy, 0, bits), r0, "R(" + x + "," + y + ")");
                assertEquals(TestTiffWriter.pix(gx, gy, 1, bits), g0, "G(" + x + "," + y + ")");
                assertEquals(TestTiffWriter.pix(gx, gy, 2, bits), b0, "B(" + x + "," + y + ")");
            }
        }
    }

    @Test
    void rgbRaw8() throws IOException {
        try (CogReader r = openOneLevel(new TestTiffWriter.LevelSpec(32, 32, 1, 1, 8, false))) {
            checkRgb(r, 8);
        }
    }

    @Test
    void rgbDeflate8() throws IOException {
        try (CogReader r = openOneLevel(new TestTiffWriter.LevelSpec(32, 32, 8, 1, 8, false))) {
            checkRgb(r, 8);
        }
    }

    @Test
    void rgbPackBits8() throws IOException {
        try (CogReader r = openOneLevel(new TestTiffWriter.LevelSpec(32, 32, 32773, 1, 8, false))) {
            checkRgb(r, 8);
        }
    }

    @Test
    void rgbLzw8() throws IOException {
        try (CogReader r = openOneLevel(new TestTiffWriter.LevelSpec(32, 32, 5, 1, 8, false))) {
            checkRgb(r, 8);
        }
    }

    @Test
    void grey8WithHorizontalPredictorDeflate() throws IOException {
        try (CogReader r = openOneLevel(new TestTiffWriter.LevelSpec(48, 32, 8, 2, 8, true))) {
            BufferedImage img = r.getTile(0, 2, 1);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int v = img.getRaster().getSample(x, y, 0);
                    assertEquals(TestTiffWriter.pix(32 + x, 16 + y, 0, 8), v,
                            "gris(" + x + "," + y + ")");
                }
            }
        }
    }

    @Test
    void grey16BigTiffStyleOffsets() throws IOException {
        try (CogReader r = openOneLevel(new TestTiffWriter.LevelSpec(32, 32, 8, 1, 16, true))) {
            BufferedImage img = r.getTile(0, 1, 1);
            assertEquals(BufferedImage.TYPE_USHORT_GRAY, img.getType());
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int v = img.getRaster().getSample(x, y, 0);
                    assertEquals(TestTiffWriter.pix(16 + x, 16 + y, 0, 16), v);
                }
            }
        }
    }

    /**
     * Tuile JPEG ecrite par GDAL (COG driver) : pas de DQT dans le flux,
     * tables de quantification dans la balise JPEGTables (347).
     * Fixture extraite de cog-paris-3857.tif (gdalwarp -of COG -co COMPRESS=JPEG).
     */
    @Test
    void jpegTileWithExternalTables() throws IOException {
        byte[] tables = readFile("gdal-jpeg-tables.bin");
        byte[] tile = readFile("gdal-jpeg-tile.bin");
        assertEquals(0xFF, tile[0] & 0xFF);
        assertEquals(0xD8, tile[1] & 0xFF);
        CogInfo.Level lvl = new CogInfo.Level(0, 512, 512, 512, 512, 7, 6, 1,
                3, new int[] {8, 8, 8}, new int[0], 1, 0, false,
                new long[] {0}, new long[] {tile.length}, tables);
        BufferedImage img = TileDecoder.decode(lvl, tile);
        assertEquals(512, img.getWidth());
        assertEquals(512, img.getHeight());
    }

    private static byte[] readFile(String name) throws IOException {
        try (var in = TileDecoderTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("fixture manquante : " + name);
            }
            return in.readAllBytes();
        }
    }
}
