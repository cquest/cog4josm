package org.openstreetmap.josm.plugins.cog.reader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

class TiffParserTest {

    private static CogInfo parseTwoLevels(int compression, int predictor, int bits, boolean grey) throws IOException {
        byte[] tiff = TestTiffWriter.write(List.of(
                new TestTiffWriter.LevelSpec(64, 48, compression, predictor, bits, grey),
                new TestTiffWriter.LevelSpec(32, 24, compression, predictor, bits, grey)),
                3857, 10.0, 500000.0, 6250000.0);
        return TiffParser.parse(new InMemoryByteSource(tiff));
    }

    @Test
    void parsesPyramidAndGeo() throws IOException {
        CogInfo ci = parseTwoLevels(1, 1, 8, false);
        assertEquals(2, ci.levelCount());
        assertEquals(64, ci.level(0).width);
        assertEquals(48, ci.level(0).height);
        assertEquals(32, ci.level(1).width);
        assertEquals(4, ci.level(0).numXTiles());
        assertEquals(3, ci.level(0).numYTiles());
        assertEquals(3857, ci.crsCode);
        assertEquals(500000.0, ci.extentMinX(), 1e-9);
        assertEquals(6250000.0, ci.extentMaxY(), 1e-9);
        assertEquals(64 * 10.0, ci.extentMaxX() - ci.extentMinX(), 1e-9);
        assertEquals(48 * 10.0, ci.extentMaxY() - ci.extentMinY(), 1e-9);
        assertEquals(10.0, ci.resolution(0), 1e-9);
        assertEquals(20.0, ci.resolution(1), 1e-9);
        assertEquals(0, ci.bestLevelFor(10.5));
        assertEquals(1, ci.bestLevelFor(19.0));
    }

    @Test
    void tileOffsetsPointToDistinctData() throws IOException {
        byte[] tiff = TestTiffWriter.write(List.of(
                new TestTiffWriter.LevelSpec(32, 32, 1, 1, 8, true)), 3857, 1.0, 0.0, 0.0);
        CogInfo ci = TiffParser.parse(new InMemoryByteSource(tiff));
        CogInfo.Level l = ci.level(0);
        assertEquals(4, l.tileOffsets.length);
        for (int i = 0; i < l.tileOffsets.length; i++) {
            for (int j = i + 1; j < l.tileOffsets.length; j++) {
                assertTrue(l.tileOffsets[i] != l.tileOffsets[j], "distinct offsets");
            }
            assertTrue(l.tileOffsets[i] + l.tileByteCounts[i] <= tiff.length, "within the file");
        }
    }

    @Test
    void rejectsStrippedTiff() {
        // minimal TIFF without tile tags: must fail with a clear message
        byte[] fake = new byte[64];
        fake[0] = 'I';
        fake[1] = 'I';
        fake[2] = 42;
        fake[4] = 8; // first IFD at 8, 0 entries
        IOException e = assertThrows(IOException.class, () -> TiffParser.parse(new InMemoryByteSource(fake)));
        assertTrue(e.getMessage().contains("tiled"));
    }

    @Test
    void readsGeoKeys4326() throws IOException {
        byte[] tiff = TestTiffWriter.write(List.of(
                new TestTiffWriter.LevelSpec(16, 16, 1, 1, 8, true)), 4326, 0.001, 2.0, 49.0);
        CogInfo ci = TiffParser.parse(new InMemoryByteSource(tiff));
        assertEquals(4326, ci.crsCode);
        assertArrayEquals(new int[]{8}, ci.level(0).bitsPerSample);
        org.junit.jupiter.api.Assertions.assertNull(ci.customCrs);
    }

    @Test
    void readsCustomGeoKeys() throws IOException {        double[] d = new double[9];
        int[] keys = TestTiffWriter.customKeysLcc(d);
        byte[] tiff = TestTiffWriter.write(List.of(
                new TestTiffWriter.LevelSpec(16, 16, 1, 1, 8, true)),
                32767, keys, d, 100.0, 700000.0, 6600000.0);
        CogInfo ci = TiffParser.parse(new InMemoryByteSource(tiff));
        assertEquals(32767, ci.crsCode);
        org.junit.jupiter.api.Assertions.assertNotNull(ci.customCrs);
        assertEquals(Integer.valueOf(1), ci.customCrs.key(CustomCrs.KEY_MODEL_TYPE));
        assertEquals(Integer.valueOf(32767), ci.customCrs.key(CustomCrs.KEY_PROJ_CODE));
        assertEquals(Integer.valueOf(8), ci.customCrs.key(CustomCrs.KEY_PROJ_METHOD_CODE));
        assertEquals(Integer.valueOf(9001), ci.customCrs.key(CustomCrs.KEY_PROJ_LINEAR_UNITS));
        assertEquals(49.0, ci.customCrs.dbl(CustomCrs.KEY_PROJ_STD_PARALLEL2), 1e-12);
        assertEquals(6600000.0, ci.customCrs.dbl(CustomCrs.KEY_PROJ_FALSE_ORIGIN_NORTHING), 1e-12);
        assertEquals(6378137.0, ci.customCrs.dbl(CustomCrs.KEY_GEOG_SEMI_MAJOR), 1e-9);
        org.junit.jupiter.api.Assertions.assertNotNull(ci.customCrs.citation);
        // the parser must not confuse an inline key with a DOUBLE reference
        org.junit.jupiter.api.Assertions.assertNull(ci.customCrs.key(CustomCrs.KEY_PROJ_STD_PARALLEL1));
        org.junit.jupiter.api.Assertions.assertTrue(ci.crsDescription().contains("custom"));
    }

    @Test
    void readsCustomGeographicKeys() throws IOException {
        double[] d = {6378249.2, 293.4660212936269, 0.0, 2.5};
        int[] keys = {
                1, 1, 0, 8,
                1024, 0, 1, 2,
                2048, 0, 1, 32767,
                2054, 0, 1, 9102,
                2056, 0, 1, 32767,
                2057, 34736, 1, 0,
                2059, 34736, 1, 1,
                2061, 34736, 1, 2,
                2600, 34737, 5, 0, // ASCII key (GCS name): ignored but parseable
        };
        byte[] tiff = TestTiffWriter.write(List.of(
                new TestTiffWriter.LevelSpec(16, 16, 1, 1, 8, true)),
                32767, keys, d, 0.001, 2.0, 49.0);
        CogInfo ci = TiffParser.parse(new InMemoryByteSource(tiff));
        assertEquals(32767, ci.crsCode);
        assertEquals(Integer.valueOf(2), ci.customCrs.key(CustomCrs.KEY_MODEL_TYPE));
        assertEquals(6378249.2, ci.customCrs.dbl(CustomCrs.KEY_GEOG_SEMI_MAJOR), 1e-9);
        assertEquals(0.0, ci.customCrs.dbl(CustomCrs.KEY_GEOG_PRIME_MERIDIAN), 1e-12);
    }

    @Test
    void readsHybridRegisteredCodeWithGeoKeysParams() throws IOException {
        // PIX4Dmatic case: registered EPSG code (3943) BUT method declared as
        // "user-defined" (3074=32767) with full DOUBLE parameters ->
        // customCrs must be kept (Crs.resolve falls back to it if the code is unknown)
        double[] d = new double[9];
        int[] keys = TestTiffWriter.customKeysLcc(d, 3943, 4171);
        byte[] tiff = TestTiffWriter.write(List.of(
                new TestTiffWriter.LevelSpec(16, 16, 1, 1, 8, true)),
                3943, keys, d, 100.0, 700000.0, 6600000.0);
        CogInfo ci = TiffParser.parse(new InMemoryByteSource(tiff));
        assertEquals(3943, ci.crsCode);
        org.junit.jupiter.api.Assertions.assertNotNull(ci.customCrs);
        assertEquals(Integer.valueOf(32767), ci.customCrs.key(CustomCrs.KEY_PROJ_COORD_TRANS));
        assertTrue(ci.crsDescription().startsWith("EPSG:3943"));
        assertTrue(ci.crsDescription().contains("custom"));
    }

    @Test
    void sortsHugePyramidWithoutIntOverflow() {
        // PIX4Dmatic COG 131072x98304: area = 3x2^32 = 0 as int, and the overview
        // 65536x49152 = 3x2^30 goes negative: the sort used to place an overview
        // (NewSubfileType=1) first -> spurious "missing full-resolution IFD"
        // error. The area must be computed as long.
        java.util.List<CogInfo.Level> levels = new java.util.ArrayList<>();
        levels.add(new CogInfo.Level(2, 32768, 24576, 128, 128, 7, 6, 1,
                3, new int[]{8, 8, 8}, new int[0], 1, 1, false, new long[0], new long[0]));
        levels.add(new CogInfo.Level(0, 131072, 98304, 128, 128, 7, 6, 1,
                3, new int[]{8, 8, 8}, new int[0], 1, 0, false, new long[0], new long[0]));
        levels.add(new CogInfo.Level(1, 65536, 49152, 128, 128, 7, 6, 1,
                3, new int[]{8, 8, 8}, new int[0], 1, 1, false, new long[0], new long[0]));
        TiffParser.sortLevelsByAreaDesc(levels);
        assertEquals(131072, levels.get(0).width);
        assertEquals(65536, levels.get(1).width);
        assertEquals(32768, levels.get(2).width);
    }
}
