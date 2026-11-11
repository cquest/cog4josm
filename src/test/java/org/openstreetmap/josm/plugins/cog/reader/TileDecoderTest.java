package org.openstreetmap.josm.plugins.cog.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
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
                            "grey(" + x + "," + y + ")");
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
     * JPEG tile written by GDAL (COG driver): no DQT in the stream,
     * quantization tables in the JPEGTables tag (347).
     * Fixture extracted from test_data/cog-paris-3857.tif (gdalwarp -of COG -co COMPRESS=JPEG).
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

    // --- security non-regression (audit POCs: bombs and alpha stride) ---

    /** 300 MB of zeros deflated in streaming (the compressed form stays small). */
    private static byte[] deflatedZeros(int megabytes) {
        java.util.zip.Deflater d = new java.util.zip.Deflater(9);
        ByteArrayOutputStream comp = new ByteArrayOutputStream();
        byte[] zeros = new byte[1 << 20];
        byte[] buf = new byte[8192];
        for (int i = 0; i < megabytes; i++) {
            d.setInput(zeros);
            while (!d.needsInput()) {
                comp.write(buf, 0, d.deflate(buf));
            }
        }
        d.finish();
        while (!d.finished()) {
            comp.write(buf, 0, d.deflate(buf));
        }
        d.end();
        return comp.toByteArray();
    }

    private static CogInfo.Level greyLevel(int tile, int compression) {
        return new CogInfo.Level(0, tile, tile, tile, tile, compression, 1, 1,
                1, new int[]{8}, new int[0], 1, 0, false, new long[]{0}, new long[]{1});
    }

    @Test
    void inflateBombRejected() throws IOException {
        // audit POC: a few hundred KB of deflated zeros must NOT be expanded
        // beyond the raster the level declares (64x64 grey = 4096 bytes)
        byte[] bomb = deflatedZeros(300);
        IOException e = assertThrows(IOException.class,
                () -> TileDecoder.decode(greyLevel(64, 8), bomb));
        assertTrue(e.getMessage().contains("bomb"), e.getMessage());
    }

    @Test
    void packBitsBombRejected() throws IOException {
        // repeat runs of 128 bytes: 2 input bytes -> 128 output bytes
        byte[] in = new byte[8192];
        java.util.Arrays.fill(in, (byte) 0x81);
        for (int i = 1; i < in.length; i += 2) {
            in[i] = 0;
        }
        IOException e = assertThrows(IOException.class,
                () -> TileDecoder.decode(greyLevel(64, 32773), in));
        assertTrue(e.getMessage().contains("bomb"), e.getMessage());
    }

    @Test
    void lzwExplosionRejected() throws IOException {
        // LZW chain growth: cap must fire before the output outgrows the raster
        byte[] in = new byte[1 << 16];
        IOException e = assertThrows(IOException.class,
                () -> TileDecoder.decode(greyLevel(64, 5), in));
        // either rejected as bomb, or as invalid/truncated data: no OOM
        assertTrue(e.getMessage().contains("bomb") || e.getMessage().contains("LZW"),
                e.getMessage());
    }

    @Test
    void rgbaWithExtraSamplesUsesStoredStride() throws IOException {
        // audit POC: GDAL writes RGBA as SamplesPerPixel=4 + ExtraSamples: the
        // alpha is INSIDE the 4 stored samples, the stride must stay 4 bytes
        // (the old code used 5 -> shifted picture and out-of-bounds reads)
        byte[] px = new byte[2 * 2 * 4];
        int i = 0;
        for (int k = 0; k < 4; k++) {
            px[i++] = (byte) (k * 10 + 5); // R
            px[i++] = (byte) (k * 20 + 6); // G
            px[i++] = (byte) (k * 30 + 7); // B
            px[i++] = (byte) (k * 40 + 8); // A
        }
        CogInfo.Level lvl = new CogInfo.Level(0, 2, 2, 2, 2, 1, 2, 1,
                4, new int[]{8, 8, 8, 8}, new int[]{2}, 1, 0, false,
                new long[]{0}, new long[]{px.length});
        BufferedImage img = TileDecoder.decode(lvl, px);
        // last pixel (k=3): R=35 G=66 B=97 A=128
        assertEquals((128 << 24) | (35 << 16) | (66 << 8) | 97, img.getRGB(1, 1));
    }

    @Test
    void jpegTileBiggerThanDeclaredTileRejected() throws IOException {
        // audit POC: a small stream whose SOF declares 64x64 under a level
        // with 16x16 tiles (decompression bomb) must be rejected on the header
        BufferedImage src = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(src, "JPEG", jpeg);
        byte[] raw = jpeg.toByteArray();
        CogInfo.Level lvl = new CogInfo.Level(0, 16, 16, 16, 16, 6, 2, 1,
                3, new int[]{8, 8, 8}, new int[0], 1, 0, false,
                new long[]{0}, new long[]{raw.length});
        IOException e = assertThrows(IOException.class, () -> TileDecoder.decode(lvl, raw));
        assertTrue(e.getMessage().contains("exceeds the declared tile size"), e.getMessage());
    }

    private static byte[] readFile(String name) throws IOException {
        try (var in = TileDecoderTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("missing fixture: " + name);
            }
            return in.readAllBytes();
        }
    }
}
