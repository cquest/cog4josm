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
                assertTrue(l.tileOffsets[i] != l.tileOffsets[j], "offsets distincts");
            }
            assertTrue(l.tileOffsets[i] + l.tileByteCounts[i] <= tiff.length, "dans le fichier");
        }
    }

    @Test
    void rejectsStrippedTiff() {
        // TIFF minimal sans tags de tuiles : doit echouer avec un message clair
        byte[] fake = new byte[64];
        fake[0] = 'I';
        fake[1] = 'I';
        fake[2] = 42;
        fake[4] = 8; // premier IFD a 8, 0 entrees
        IOException e = assertThrows(IOException.class, () -> TiffParser.parse(new InMemoryByteSource(fake)));
        assertTrue(e.getMessage().contains("tuile"));
    }

    @Test
    void readsGeoKeys4326() throws IOException {
        byte[] tiff = TestTiffWriter.write(List.of(
                new TestTiffWriter.LevelSpec(16, 16, 1, 1, 8, true)), 4326, 0.001, 2.0, 49.0);
        CogInfo ci = TiffParser.parse(new InMemoryByteSource(tiff));
        assertEquals(4326, ci.crsCode);
        assertArrayEquals(new int[]{8}, ci.level(0).bitsPerSample);
    }
}
