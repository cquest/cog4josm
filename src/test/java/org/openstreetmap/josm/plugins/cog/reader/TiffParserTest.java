package org.openstreetmap.josm.plugins.cog.reader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
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
        long[] offs = l.tileOffsets();
        long[] cnts = l.tileByteCounts();
        assertEquals(4, offs.length);
        for (int i = 0; i < offs.length; i++) {
            for (int j = i + 1; j < offs.length; j++) {
                assertTrue(offs[i] != offs[j], "distinct offsets");
            }
            assertTrue(offs[i] + cnts[i] <= tiff.length, "within the file");
        }
    }

    @Test
    void hugeTileTablesLoadOnlyOnFirstLevelUse() throws IOException {
        // lazy per-level IFD parsing: the 33x33-tile tables (2 x 4,356 bytes,
        // above the 4,096-byte threshold) must not be fetched at parse time
        byte[] tiff = TestTiffWriter.writeTail(List.of(
                new TestTiffWriter.LevelSpec(528, 528, 1, 1, 8, true),
                new TestTiffWriter.LevelSpec(16, 16, 1, 1, 8, true)), 3857, 1.0, 0.0, 0.0);
        final InMemoryByteSource mem = new InMemoryByteSource(tiff);
        final int[] reads = new int[1];
        final long[] bytes = new long[1];
        ByteSource counting = new ByteSource() {
            @Override
            public long size() throws IOException {
                return mem.size();
            }

            @Override
            public boolean supportsRanges() {
                return true;
            }

            @Override
            public byte[] read(long offset, int length) throws IOException {
                reads[0]++;
                byte[] d = mem.read(offset, length);
                bytes[0] += d.length;
                return d;
            }

            @Override
            public void close() {
            }
        };
        CogInfo ci = TiffParser.parse(counting);
        // header + IFD chain + tiny eager values (L1 tables 8 B, geo block):
        // far below the 8,712 bytes of the deferred L0 tables
        assertTrue(bytes[0] < 8_000, "parse alone: " + bytes[0] + " bytes read");
        CogInfo.Level l0 = ci.level(0);
        assertEquals(33 * 33, l0.numXTiles() * l0.numYTiles());
        int readsAfterParse = reads[0];
        l0.ensureTileArrays();
        assertTrue(reads[0] > readsAfterParse, "the tables are fetched on first use");
        assertEquals(33 * 33, l0.tileOffsets().length);
        assertEquals(33 * 33, l0.tileByteCounts().length);
        int readsAfterLoad = reads[0];
        l0.ensureTileArrays();
        assertEquals(readsAfterLoad, reads[0], "ensureTileArrays is idempotent");
        // small L1 tables stayed eager: no further read when used
        assertEquals(1, ci.level(1).tileOffsets().length);
        assertEquals(readsAfterLoad, reads[0], "eager tables never re-fetched");
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
        // customCrs must be kept (Crs.resolve falls back to it when the code
        // is not in the registry; for a known code the registry wins)
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

    // --- crafted raw TIFFs (security audit POCs): hostile counts and tables ---

    private record RawEntry(int tag, int type, long count, long value, byte[] ext) {
    }

    /** One little-endian IFD at offset 8, external values appended after it. */
    private static byte[] rawTiff(RawEntry... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('I');
        out.write('I');
        w16(out, 42);
        w32(out, 8);
        w16(out, entries.length);
        long extPos = 8 + 2 + 12L * entries.length + 4;
        for (RawEntry e : entries) {
            w16(out, e.tag());
            w16(out, e.type());
            w32(out, e.count());
            if (e.ext() == null) {
                w32(out, e.value());
            } else {
                w32(out, extPos);
                extPos += e.ext().length;
            }
        }
        w32(out, 0);
        for (RawEntry e : entries) {
            if (e.ext() != null) {
                out.write(e.ext());
            }
        }
        return out.toByteArray();
    }

    private static void w16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write(v >> 8 & 0xFF);
    }

    private static void w32(ByteArrayOutputStream o, long v) {
        for (int i = 0; i < 4; i++) {
            o.write((int) (v >> (8 * i)) & 0xFF);
        }
    }

    private static byte[] leDoubles(double... v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (double d : v) {
            long b = Double.doubleToLongBits(d);
            for (int i = 0; i < 8; i++) {
                o.write((int) (b >> (8 * i)) & 0xFF);
            }
        }
        return o.toByteArray();
    }

    @Test
    void clampsHostileDeclaredCountToDataSize() throws IOException {
        // audit POC: DOUBLE tag declaring 2^32-1 values (NegativeArraySize) or
        // 2^28 values (multi-GB allocation) in a file of a few dozen bytes:
        // the fetched data must bound the array actually built
        byte[] file = rawTiff(new RawEntry(700, 12, 0xFFFFFFFFL, 0, new byte[8]));
        List<TiffParser.Ifd> ifds = TiffParser.readIfds(new InMemoryByteSource(file), null);
        TiffParser.IfdEntry e = ifds.get(0).entry(700);
        assertEquals(1, e.doubles.length);

        byte[] file2 = rawTiff(new RawEntry(700, 12, 1L << 28, 0, new byte[8]));
        List<TiffParser.Ifd> ifds2 = TiffParser.readIfds(new InMemoryByteSource(file2), null);
        assertEquals(1, ifds2.get(0).entry(700).doubles.length);
    }

    @Test
    void rejectsShortTileOffsetTable() throws IOException {
        // audit POC: 100000x100000 image with 1x1 tiles (10^10 tiles) but only
        // 4 TileOffsets/TileByteCounts entries: the tile index escaped the arrays
        // (ArrayIndexOutOfBoundsException) - parse must reject the file
        byte[] offs = new byte[16];
        byte[] cnts = new byte[16];
        byte[] file = rawTiff(
                new RawEntry(254, 4, 1, 0, null),
                new RawEntry(256, 4, 1, 100000, null),
                new RawEntry(257, 4, 1, 100000, null),
                new RawEntry(258, 3, 1, 8, null),
                new RawEntry(259, 3, 1, 1, null),
                new RawEntry(262, 3, 1, 1, null),
                new RawEntry(277, 3, 1, 1, null),
                new RawEntry(322, 4, 1, 1, null),
                new RawEntry(323, 4, 1, 1, null),
                new RawEntry(324, 4, 4, 0, offs),
                new RawEntry(325, 4, 4, 0, cnts),
                new RawEntry(33550, 12, 3, 0, leDoubles(1, 1, 0)),
                new RawEntry(33922, 12, 6, 0, leDoubles(0, 0, 0, 500000, 1000000, 0)));
        IOException e = assertThrows(IOException.class, () -> TiffParser.parse(new InMemoryByteSource(file)));
        assertTrue(e.getMessage().contains("TileOffsets"), e.getMessage());
    }

    @Test
    void oversizedTileByteCountStaysHandled() throws IOException {
        // a declared tile byte count beyond 2 GB (int overflow in the old
        // copyOfRange arithmetic) must surface as an IOException, not a crash
        byte[] offs = new byte[4];
        byte[] cnts = new byte[4];
        offs[0] = 100; // offset beyond the end of this tiny file
        cnts[0] = (byte) 0x80; // 0x80000000 byte count
        byte[] file = rawTiff(
                new RawEntry(254, 4, 1, 0, null),
                new RawEntry(256, 4, 1, 2, null),
                new RawEntry(257, 4, 1, 2, null),
                new RawEntry(258, 3, 1, 8, null),
                new RawEntry(259, 3, 1, 1, null),
                new RawEntry(262, 3, 1, 1, null),
                new RawEntry(277, 3, 1, 1, null),
                new RawEntry(322, 4, 1, 2, null),
                new RawEntry(323, 4, 1, 2, null),
                new RawEntry(324, 4, 1, 0, offs),
                new RawEntry(325, 4, 1, 0, cnts),
                new RawEntry(33550, 12, 3, 0, leDoubles(1, 1, 0)),
                new RawEntry(33922, 12, 6, 0, leDoubles(0, 0, 0, 500000, 1000000, 0)));
        try (CogReader r = CogReader.open(new InMemoryByteSource(file))) {
            // bounded garbage is tolerated by the decoder; any unchecked
            // exception (NegativeArraySize, IllegalArgument, AIOOBE) fails the test
            try {
                r.getTile(0, 0, 0);
            } catch (IOException expected) {
                // truncated tile data: normal rejection
            }
        }
    }

    @Test
    void rejectsAbsurdTileSize() throws IOException {
        // a tile of 8192x8192 would make decoded rasters gigantic: bounded
        byte[] file = rawTiff(
                new RawEntry(256, 4, 1, 64, null),
                new RawEntry(257, 4, 1, 64, null),
                new RawEntry(322, 4, 1, 8192, null),
                new RawEntry(323, 4, 1, 8192, null),
                new RawEntry(324, 4, 1, 0, new byte[4]),
                new RawEntry(325, 4, 1, 0, new byte[4]));
        IOException e = assertThrows(IOException.class, () -> TiffParser.parse(new InMemoryByteSource(file)));
        assertTrue(e.getMessage().contains("tile size"), e.getMessage());
    }

    // --- BigTIFF (magic 43, 64-bit offsets: typical of PIX4Dmatic 3.8 GB COGs) ---

    private static byte[] rawBigTiff(RawEntry... entries) throws IOException {
        // header: II, 43, offset size 8, constant 0, first IFD at 16
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('I');
        out.write('I');
        w16(out, 43);
        w16(out, 8);
        w16(out, 0);
        w64(out, 16);
        w64(out, entries.length);
        long extPos = 16 + 8 + 20L * entries.length + 8;
        for (RawEntry e : entries) {
            w16(out, e.tag());
            w16(out, e.type());
            w64(out, e.count());
            if (e.ext() == null) {
                w64(out, e.value());
            } else {
                w64(out, extPos);
                extPos += e.ext().length;
            }
        }
        w64(out, 0);
        for (RawEntry e : entries) {
            if (e.ext() != null) {
                out.write(e.ext());
            }
        }
        return out.toByteArray();
    }

    private static void w64(ByteArrayOutputStream o, long v) {
        for (int i = 0; i < 8; i++) {
            o.write((int) (v >> (8 * i)) & 0xFF);
        }
    }

    private static byte[] bigTiffTiled2x2() throws IOException {
        // 2x2 image, one 2x2 tile (LONG8 offsets/counts stored inline in the
        // 8-byte value field), tile data and geo double values appended
        byte[] tile = {10, 20, 30, 40};
        byte[] file = rawBigTiff(
                new RawEntry(256, 3, 1, 2, null),
                new RawEntry(257, 3, 1, 2, null),
                new RawEntry(258, 3, 1, 8, null),
                new RawEntry(259, 3, 1, 1, null),
                new RawEntry(262, 3, 1, 1, null),
                new RawEntry(277, 3, 1, 1, null),
                new RawEntry(322, 3, 1, 2, null),
                new RawEntry(323, 3, 1, 2, null),
                new RawEntry(324, 16, 1, 0, null), // patched below: inline offset of tile
                new RawEntry(325, 16, 1, tile.length, null),
                new RawEntry(33550, 12, 3, 0, leDoubles(1, 1, 0)),
                new RawEntry(33922, 12, 6, 0, leDoubles(0, 0, 0, 500000, 6250000, 0)));
        // the tile sits right after the last external value: rewrite the
        // inline LONG8 offset of tag 324 (entry index 8, value at 16+8+8*20+12)
        int entry = 16 + 8 + 8 * 20;
        long tileOff = 16 + 8 + 20L * 12 + 8 + 3 * 8 + 6 * 8;
        for (int i = 0; i < 8; i++) {
            file[entry + 12 + i] = (byte) (tileOff >>> (8 * i));
        }
        byte[] full = Arrays.copyOf(file, (int) tileOff + tile.length);
        System.arraycopy(tile, 0, full, (int) tileOff, tile.length);
        return full;
    }

    @Test
    void parsesBigTiffWithInlineLong8Offsets() throws IOException {
        byte[] file = bigTiffTiled2x2();
        CogInfo ci = TiffParser.parse(new InMemoryByteSource(file));
        assertEquals(1, ci.levels.size());
        CogInfo.Level lvl = ci.levels.get(0);
        assertEquals(2, lvl.width);
        assertEquals(2, lvl.height);
        assertEquals(1, lvl.numXTiles());
        assertEquals(1, lvl.numYTiles());
        try (CogReader r = CogReader.open(new InMemoryByteSource(file))) {
            java.awt.image.BufferedImage img = r.getTile(0, 0, 0);
            assertEquals(2, img.getWidth());
            // raster samples (getRGB on TYPE_BYTE_GRAY goes through the
            // default gray ICC profile, the plugin consumers read the raster)
            assertEquals(10, img.getRaster().getSample(0, 0, 0));
            assertEquals(40, img.getRaster().getSample(1, 1, 0));
        }
    }

    @Test
    void bigTiffRejectsAbsurdEntryCount() throws IOException {
        // 64-bit entry count far beyond any plausible IFD: refused on the header
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('I');
        out.write('I');
        w16(out, 43);
        w16(out, 8);
        w16(out, 0);
        w64(out, 16);
        w64(out, 1L << 40);
        IOException e = assertThrows(IOException.class,
                () -> TiffParser.readIfds(new InMemoryByteSource(out.toByteArray()), null));
        assertTrue(e.getMessage().contains("IFD entries"), e.getMessage());
    }

    @Test
    void bigTiffRejectsWrongOffsetSize() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('I');
        out.write('I');
        w16(out, 43);
        w16(out, 4);
        w16(out, 0);
        w64(out, 16);
        IOException e = assertThrows(IOException.class,
                () -> TiffParser.readIfds(new InMemoryByteSource(out.toByteArray()), null));
        assertTrue(e.getMessage().contains("offset size"), e.getMessage());
    }

    @Test
    void bigTiffClampsHostile64BitCount() throws IOException {
        // DOUBLE tag declaring 2^64-1 values: the fetched data must still bound
        // the array actually built (one 8-byte value in the file)
        byte[] file = rawBigTiff(new RawEntry(700, 12, -1L, 0, new byte[8]));
        List<TiffParser.Ifd> ifds = TiffParser.readIfds(new InMemoryByteSource(file), null);
        assertEquals(1, ifds.get(0).entry(700).doubles.length);
    }

    // --- interleaved transparency mask IFDs (GDAL COG with alpha) ---

    /** Two chained classic IFDs; external values follow both IFD tables. */
    private static byte[] rawTiffChain(RawEntry[]... ifds) throws IOException {
        long[] ifdPos = new long[ifds.length];
        long pos = 8;
        for (int i = 0; i < ifds.length; i++) {
            ifdPos[i] = pos;
            pos += 2 + 12L * ifds[i].length + 4;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('I');
        out.write('I');
        w16(out, 42);
        w32(out, 8);
        long extPos = pos;
        for (int i = 0; i < ifds.length; i++) {
            w16(out, ifds[i].length);
            for (RawEntry e : ifds[i]) {
                w16(out, e.tag());
                w16(out, e.type());
                w32(out, e.count());
                if (e.ext() == null) {
                    w32(out, e.value());
                } else {
                    w32(out, extPos);
                    extPos += e.ext().length;
                }
            }
            w32(out, i + 1 < ifds.length ? ifdPos[i + 1] : 0);
        }
        for (RawEntry[] es : ifds) {
            for (RawEntry e : es) {
                if (e.ext() != null) {
                    out.write(e.ext());
                }
            }
        }
        return out.toByteArray();
    }

    @Test
    void skipsInterleavedMaskIfd() throws IOException {
        // Anglet-style COG: RGB level then same-size photometric=4 mask IFD.
        // The mask must not become a pyramid level (it would swap RGB for a
        // 1-bit mask at alternating zooms).
        int tileOff = 8 + (2 + 12 * 12 + 4) + (2 + 12 * 10 + 4) + 24 + 48;
        byte[] file0 = rawTiffChain(
                new RawEntry[]{
                        new RawEntry(256, 3, 1, 2, null), new RawEntry(257, 3, 1, 2, null),
                        new RawEntry(258, 3, 1, 8, null), new RawEntry(259, 3, 1, 1, null),
                        new RawEntry(262, 3, 1, 1, null), new RawEntry(277, 3, 1, 1, null),
                        new RawEntry(322, 3, 1, 2, null), new RawEntry(323, 3, 1, 2, null),
                        new RawEntry(324, 4, 1, tileOff, null), new RawEntry(325, 4, 1, 4, null),
                        new RawEntry(33550, 12, 3, 0, leDoubles(1, 1, 0)),
                        new RawEntry(33922, 12, 6, 0, leDoubles(0, 0, 0, 500000, 6250000, 0))},
                new RawEntry[]{
                        new RawEntry(256, 3, 1, 2, null), new RawEntry(257, 3, 1, 2, null),
                        new RawEntry(258, 3, 1, 1, null), new RawEntry(259, 3, 1, 1, null),
                        new RawEntry(262, 3, 1, 4, null), new RawEntry(277, 3, 1, 1, null),
                        new RawEntry(322, 3, 1, 2, null), new RawEntry(323, 3, 1, 2, null),
                        new RawEntry(324, 4, 1, tileOff, null), new RawEntry(325, 4, 1, 4, null)});
        byte[] file = Arrays.copyOf(file0, tileOff + 4);
        file[tileOff] = 10;
        file[tileOff + 1] = 20;
        file[tileOff + 2] = 30;
        file[tileOff + 3] = 40;
        CogInfo ci = TiffParser.parse(new InMemoryByteSource(file));
        assertEquals(1, ci.levels.size());
        assertEquals(1, ci.levels.get(0).photometric);
        try (CogReader r = CogReader.open(new InMemoryByteSource(file))) {
            java.awt.image.BufferedImage img = r.getTile(0, 0, 0);
            assertEquals(10, img.getRaster().getSample(0, 0, 0));
        }
    }
}
