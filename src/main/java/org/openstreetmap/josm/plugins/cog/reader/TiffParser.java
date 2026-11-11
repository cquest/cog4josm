package org.openstreetmap.josm.plugins.cog.reader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal TIFF/BigTIFF/GeoTIFF parser for COGs: header, IFD chain,
 * TileOffsets/TileByteCounts arrays, GeoTIFF geographic tags. It reads only the
 * strictly necessary bytes through a {@link ByteSource}. BigTIFF (magic 43,
 * 64-bit offsets, 20-byte IFD entries) is read as well as classic TIFF (42).
 */
public final class TiffParser {

    public static final int TAG_NEW_SUBFILETYPE = 254;
    public static final int TAG_IMAGE_WIDTH = 256;
    public static final int TAG_IMAGE_LENGTH = 257;
    public static final int TAG_BITS_PER_SAMPLE = 258;
    public static final int TAG_COMPRESSION = 259;
    public static final int TAG_PHOTOMETRIC = 262;
    public static final int TAG_PREDICTOR = 317;
    public static final int TAG_TILE_WIDTH = 322;
    public static final int TAG_TILE_LENGTH = 323;
    public static final int TAG_TILE_OFFSETS = 324;
    public static final int TAG_TILE_BYTE_COUNTS = 325;
    public static final int TAG_JPEG_TABLES = 347;
    public static final int TAG_ROWS_PER_STRIP = 278;
    public static final int TAG_STRIP_OFFSETS = 273;
    public static final int TAG_SAMPLES_PER_PIXEL = 277;
    public static final int TAG_PLANAR_CONFIG = 284;
    public static final int TAG_EXTRA_SAMPLES = 338;
    public static final int TAG_SAMPLE_FORMAT = 339;
    public static final int TAG_MODEL_PIXEL_SCALE = 33550;
    public static final int TAG_MODEL_TIEPOINT = 33922;
    public static final int TAG_GEO_KEY_DIRECTORY = 34735;
    public static final int TAG_GEO_DOUBLE_PARAMS = 34736;
    public static final int TAG_GEO_ASCII_PARAMS = 34737;
    public static final int TAG_GDAL_METADATA = 65077;
    public static final int TAG_GDAL_NODATA = 42113;

    private static final int[] TYPE_SIZES = new int[19];
    static {
        TYPE_SIZES[1] = 1;  // BYTE
        TYPE_SIZES[2] = 1;  // ASCII
        TYPE_SIZES[3] = 2;  // SHORT
        TYPE_SIZES[4] = 4;  // LONG
        TYPE_SIZES[5] = 8;  // RATIONAL
        TYPE_SIZES[6] = 1;  // SBYTE
        TYPE_SIZES[7] = 1;  // UNDEFINED
        TYPE_SIZES[8] = 2;  // SSHORT
        TYPE_SIZES[9] = 4;  // SLONG
        TYPE_SIZES[10] = 8; // SRATIONAL
        TYPE_SIZES[11] = 4; // FLOAT
        TYPE_SIZES[12] = 8; // DOUBLE
        TYPE_SIZES[13] = 4; // IFD
        TYPE_SIZES[16] = 8; // LONG8 (BigTIFF)
        TYPE_SIZES[17] = 8; // SLONG8 (BigTIFF)
        TYPE_SIZES[18] = 8; // IFD8 (BigTIFF)
    }

    /**
     * Maximum hole between two external IFD values to still fetch them in one
     * request (they are usually stored contiguously after the IFD).
     */
    private static final int VALUE_MAX_GAP = 4096;
    /**
     * Preferred cap of one merged metadata request; a single bigger value
     * (tile offset arrays) gets its own request instead of being merged.
     */
    private static final long VALUE_MAX_BLOCK = 4L << 20;

    /**
     * Upper bound on the number of entries in one IFD: real IFDs hold tens of
     * tags; a 64-bit entry count (BigTIFF) is attacker-controlled and must not
     * drive the metadata read size.
     */
    private static final long MAX_IFD_ENTRIES = 4096;

    /**
     * Maximum supported native tile dimension: keeps decoded rasters bounded
     * (4096x4096 RGBA 8-bit = 64 MB, the {@code TileDecoder} raster cap); real
     * COGs use 128-1024 px blocks.
     */
    private static final int MAX_TILE_DIM = 4096;

    private TiffParser() {
    }

    /** One IFD entry: array of values depending on its type. */
    public static final class IfdEntry {
        public final int type;
        public final long count;
        public final long[] longs;
        public final double[] doubles;
        public final String ascii;

        IfdEntry(int type, long count, long[] longs, double[] doubles, String ascii) {
            this.type = type;
            this.count = count;
            this.longs = longs;
            this.doubles = doubles;
            this.ascii = ascii;
        }
    }

    /** An IFD (a directory of tags). */
    public static final class Ifd {
        public final long offset;
        public final long nextIfdOffset;
        final Map<Integer, IfdEntry> entries;

        Ifd(long offset, long nextIfdOffset, Map<Integer, IfdEntry> entries) {
            this.offset = offset;
            this.nextIfdOffset = nextIfdOffset;
            this.entries = entries;
        }

        public boolean has(int tag) {
            return entries.containsKey(tag);
        }

        IfdEntry entry(int tag) {
            return entries.get(tag);
        }

        public long getLong(int tag, long deflt) {
            IfdEntry e = entries.get(tag);
            return e == null || e.longs == null || e.longs.length == 0 ? deflt : e.longs[0];
        }

        public long[] getLongs(int tag) {
            IfdEntry e = entries.get(tag);
            return e == null ? null : e.longs;
        }

        public double[] getDoubles(int tag) {
            IfdEntry e = entries.get(tag);
            if (e == null) {
                return null;
            }
            if (e.doubles != null) {
                return e.doubles;
            }
            if (e.longs != null) {
                return Arrays.stream(e.longs).mapToDouble(l -> l).toArray();
            }
            return null;
        }
    }

    private static final class Header {
        boolean bigEndian;
        boolean bigTiff;
        long firstIfd;
    }

    /** Reads the header, then follows the IFD chain (max 32). */
    public static List<Ifd> readIfds(ByteSource src, boolean[] bigEndianOut) throws IOException {
        byte[] head = src.read(0, 16);
        if (head.length < 8) {
            throw new IOException("File too short to be a TIFF");
        }
        Header h = new Header();
        if (head[0] == 'I' && head[1] == 'I') {
            h.bigEndian = false;
        } else if (head[0] == 'M' && head[1] == 'M') {
            h.bigEndian = true;
        } else {
            throw new IOException("Missing TIFF signature (II/MM)");
        }
        int magic = readU16(head, 2, h.bigEndian);
        if (magic == 42) {
            h.firstIfd = Integer.toUnsignedLong(readInt(head, 4, h.bigEndian));
        } else if (magic == 43) {
            // BigTIFF: version 43 header is 16 bytes: magic(2) 43(2)
            // offsetSize(2)=8 constant(2)=0 firstIfd(8)
            if (head.length < 16) {
                throw new IOException("Truncated BigTIFF header");
            }
            int offsetSize = readU16(head, 4, h.bigEndian);
            if (offsetSize != 8) {
                throw new IOException("BigTIFF with offset size " + offsetSize + " (expected 8)");
            }
            h.bigTiff = true;
            h.firstIfd = readLong(head, 8, h.bigEndian);
            if (h.firstIfd < 16) {
                throw new IOException("BigTIFF first IFD offset inside the header: " + h.firstIfd);
            }
        } else {
            throw new IOException("Unexpected TIFF magic: " + magic);
        }
        if (bigEndianOut != null && bigEndianOut.length > 0) {
            bigEndianOut[0] = h.bigEndian;
        }

        List<Ifd> ifds = new ArrayList<>();
        long off = h.firstIfd;
        while (off != 0 && ifds.size() < 32) {
            if (off < 0) {
                throw new IOException("Invalid IFD offset: " + off);
            }
            ifds.add(readIfd(src, off, h.bigEndian, h.bigTiff));
            off = ifds.get(ifds.size() - 1).nextIfdOffset;
        }
        if (ifds.isEmpty()) {
            throw new IOException("No IFD found");
        }
        return ifds;
    }

    /**
     * IFD header + entry table + next pointer in a single read. Classic
     * entries are 12 bytes with a 4-byte value field; BigTIFF entries are 20
     * bytes (64-bit counts) with an 8-byte value field, and the entry count
     * and next-IFD pointers are 64 bits too.
     */
    private static Ifd readIfd(ByteSource src, long offset, boolean be, boolean big) throws IOException {
        int entrySize = big ? 20 : 12;
        int valueField = big ? 8 : 4;
        int ptrSize = big ? 8 : 4;
        byte[] cnt = src.read(offset, big ? 8 : 2);
        long rawCount = big ? readLong(cnt, 0, be) : readU16(cnt, 0, be);
        long declared = rawCount < 0 ? Long.MAX_VALUE : rawCount;
        // a 64-bit entry count is attacker-controlled: refuse absurd values
        // outright (real IFDs hold tens of entries, never tens of thousands)
        if (declared > MAX_IFD_ENTRIES) {
            throw new IOException("Absurd number of IFD entries: " + declared);
        }
        int n = (int) declared;
        byte[] block = src.read(offset + (big ? 8 : 2), n * entrySize + ptrSize);
        byte[] next = block.length >= n * entrySize + ptrSize
                ? Arrays.copyOfRange(block, n * entrySize, n * entrySize + ptrSize)
                : new byte[ptrSize];
        int usable = Math.min(n, block.length / entrySize);
        int[] tags = new int[usable];
        int[] types = new int[usable];
        long[] counts = new long[usable];
        long[] extOff = new long[usable];
        long[] extLen = new long[usable];
        List<long[]> ranges = new ArrayList<>();
        for (int i = 0; i < usable; i++) {
            int p = i * entrySize;
            tags[i] = readU16(block, p, be);
            types[i] = readU16(block, p + 2, be);
            if (types[i] <= 0 || types[i] >= TYPE_SIZES.length || TYPE_SIZES[types[i]] == 0) {
                types[i] = 0;
                continue;
            }
            int valPos = p + 8;
            if (big) {
                long c = readLong(block, p + 4, be);
                counts[i] = c < 0 ? Long.MAX_VALUE : c;
                valPos = p + 12;
            } else {
                counts[i] = Integer.toUnsignedLong(readInt(block, p + 4, be));
            }
            long size = TYPE_SIZES[types[i]];
            long total = counts[i] > Long.MAX_VALUE / size ? Long.MAX_VALUE : size * counts[i];
            if (total > valueField) {
                extOff[i] = big ? readLong(block, valPos, be)
                        : Integer.toUnsignedLong(readInt(block, valPos, be));
                if (extOff[i] < 0) {
                    types[i] = 0;
                    continue;
                }
                extLen[i] = Math.min(total, 1L << 26);
                ranges.add(new long[]{extOff[i], extLen[i]});
            }
        }
        // external tag values are usually stored contiguously after the IFD:
        // fetch them in merged blocks instead of one request per tag
        List<RangePlanner.Block> blocks = RangePlanner.plan(ranges, VALUE_MAX_GAP, VALUE_MAX_BLOCK);
        byte[][] blockData = new byte[blocks.size()][];
        for (int b = 0; b < blocks.size(); b++) {
            RangePlanner.Block blk = blocks.get(b);
            blockData[b] = src.read(blk.offset, blk.length);
        }
        Map<Integer, IfdEntry> entries = new HashMap<>();
        for (int i = 0; i < usable; i++) {
            if (types[i] == 0) {
                continue;
            }
            byte[] data;
            long size = TYPE_SIZES[types[i]];
            long total = counts[i] > Long.MAX_VALUE / size ? Long.MAX_VALUE : size * counts[i];
            if (total <= valueField) {
                int p = i * entrySize + (big ? 12 : 8);
                data = Arrays.copyOfRange(block, p, Math.min(p + valueField, block.length));
            } else {
                data = sliceFetched(blockData, blocks, extOff[i], (int) extLen[i], src);
            }
            entries.put(tags[i], decodeValue(types[i], counts[i], data, be));
        }
        long nextIfd = big ? readLong(next, 0, be) : Integer.toUnsignedLong(readInt(next, 0, be));
        return new Ifd(offset, nextIfd, entries);
    }

    /** Extracts one value from its pre-fetched merged block (fallback: direct read). */
    private static byte[] sliceFetched(byte[][] blockData, List<RangePlanner.Block> blocks,
                                       long off, int len, ByteSource fallback) throws IOException {
        for (int b = 0; b < blocks.size(); b++) {
            RangePlanner.Block blk = blocks.get(b);
            if (blk.covers(off, len)) {
                byte[] fetched = blockData[b];
                int from = (int) (off - blk.offset);
                int to = Math.min(from + len, fetched.length);
                if (from >= 0 && to > from) {
                    return Arrays.copyOfRange(fetched, from, to);
                }
                break;
            }
        }
        return fallback.read(off, len);
    }

    private static IfdEntry decodeValue(int type, long count, byte[] data, boolean be) {
        // Hardening: the declared count is attacker-controlled (up to 2^32-1)
        // while the fetched data is capped: allocate only what the data can back.
        long valueSize = type > 0 && type < TYPE_SIZES.length ? TYPE_SIZES[type] : 0;
        if (valueSize > 0) {
            count = Math.min(Math.max(count, 0), data.length / valueSize);
        }
        int n = (int) count;
        switch (type) {
            case 2: // ASCII
                int len = data.length;
                while (len > 0 && data[len - 1] == 0) {
                    len--;
                }
                return new IfdEntry(type, count, null, null, new String(data, 0, len, java.nio.charset.StandardCharsets.US_ASCII));
            case 1: // BYTE
            case 7: { // UNDEFINED
                long[] v = new long[n];
                for (int i = 0; i < v.length; i++) {
                    v[i] = data[Math.min(i, data.length - 1)] & 0xFFL;
                }
                return new IfdEntry(type, count, v, null, null);
            }
            case 3: // SHORT
            case 8: { // SSHORT
                long[] v = new long[n];
                for (int i = 0; i < v.length; i++) {
                    int s = readU16(data, Math.min(2 * i, data.length - 2), be);
                    v[i] = type == 8 ? (short) s : s;
                }
                return new IfdEntry(type, count, v, null, null);
            }
            case 4: // LONG
            case 13: {
                long[] v = new long[n];
                for (int i = 0; i < v.length; i++) {
                    v[i] = Integer.toUnsignedLong(readInt(data, Math.min(4 * i, data.length - 4), be));
                }
                return new IfdEntry(type, count, v, null, null);
            }
            case 9: { // SLONG
                long[] v = new long[n];
                for (int i = 0; i < v.length; i++) {
                    v[i] = readInt(data, Math.min(4 * i, data.length - 4), be);
                }
                return new IfdEntry(type, count, v, null, null);
            }
            case 16: // LONG8 (BigTIFF)
            case 17: // SLONG8 (BigTIFF)
            case 18: { // IFD8: same 8 bytes, offsets either way
                long[] v = new long[n];
                for (int i = 0; i < v.length; i++) {
                    v[i] = readLong(data, Math.min(8 * i, data.length - 8), be);
                }
                return new IfdEntry(type, count, v, null, null);
            }
            case 5: // RATIONAL
            case 10: { // SRATIONAL
                double[] v = new double[n];
                for (int i = 0; i < v.length; i++) {
                    long num = type == 10 ? readInt(data, Math.min(8 * i, data.length - 8), be)
                            : Integer.toUnsignedLong(readInt(data, Math.min(8 * i, data.length - 8), be));
                    long den = type == 10 ? readInt(data, Math.min(8 * i + 4, data.length - 4), be)
                            : Integer.toUnsignedLong(readInt(data, Math.min(8 * i + 4, data.length - 4), be));
                    v[i] = den == 0 ? 0 : (double) num / den;
                }
                return new IfdEntry(type, count, null, v, null);
            }
            case 12: { // DOUBLE
                double[] v = new double[n];
                for (int i = 0; i < v.length; i++) {
                    byte[] d = Arrays.copyOfRange(data, Math.min(8 * i, data.length - 8), Math.min(8 * i + 8, data.length));
                    long bits = 0;
                    for (int j = 0; j < 8; j++) {
                        long b = d[be ? j : 7 - j] & 0xFFL;
                        bits = (bits << 8) | b;
                    }
                    v[i] = Double.longBitsToDouble(bits);
                }
                return new IfdEntry(type, count, null, v, null);
            }
            case 11: { // FLOAT
                double[] v = new double[n];
                for (int i = 0; i < v.length; i++) {
                    byte[] f = Arrays.copyOfRange(data, Math.min(4 * i, data.length - 4), Math.min(4 * i + 4, data.length));
                    int b0 = f[be ? 3 : 0] & 0xFF | (f[be ? 2 : 1] & 0xFF) << 8
                            | (f[be ? 1 : 2] & 0xFF) << 16 | (f[be ? 0 : 3] & 0xFF) << 24;
                    v[i] = Float.intBitsToFloat(b0);
                }
                return new IfdEntry(type, count, null, v, null);
            }
            default:
                return new IfdEntry(type, count, null, null, null);
        }
    }

    /**
     * Builds a {@link CogInfo} (pyramid of levels + georeferencing) from the
     * IFDs of a COG. Levels are sorted from the finest to the coarsest.
     */
    public static CogInfo parse(ByteSource src) throws IOException {
        boolean[] be = new boolean[1];
        List<Ifd> ifds = readIfds(src, be);
        List<CogInfo.Level> levels = new ArrayList<>();
        for (Ifd ifd : ifds) {
            CogInfo.Level lvl = toLevel(ifd, levels.size(), be[0]);
            if (lvl != null) {
                levels.add(lvl);
            }
        }
        if (levels.isEmpty()) {
            Ifd first = ifds.get(0);
            if (first.has(TAG_STRIP_OFFSETS)) {
                throw new IOException("TIFF based on strips (not tiled): this is not a COG");
            }
            throw new IOException("No tiled level found (not a tiled TIFF)");
        }
        sortLevelsByAreaDesc(levels);
        for (int i = 0; i < levels.size(); i++) {
            if (levels.get(i).index != i) {
                levels.set(i, levels.get(i).withIndex(i));
            }
        }
        if ((levels.get(0).flags & 1) != 0) {
            throw new IOException("Missing full resolution IFD");
        }

        Ifd geo = ifds.get(0);
        double[] scale = geo.getDoubles(TAG_MODEL_PIXEL_SCALE);
        double[] tie = geo.getDoubles(TAG_MODEL_TIEPOINT);
        if (scale == null || scale.length < 2 || tie == null || tie.length < 6) {
            throw new IOException("GeoTIFF without ModelPixelScale/ModelTiepoint: unknown georeferencing");
        }
        double scaleX = Math.abs(scale[0]);
        double scaleY = Math.abs(scale[1]);
        if (scaleX <= 0 || scaleY <= 0) {
            throw new IOException("null ModelPixelScale");
        }
        double originX = tie[3] - tie[0] * scaleX;
        double originY = tie[4] + tie[1] * scaleY;

        int crs = crsFromGeoKeys(geo);
        CustomCrs custom = null;
        if (crs == 0 || crs == CustomCrs.USER_DEFINED) {
            // missing code or "user-defined": the GeoTIFF keys describe the CRS
            custom = customCrsFromGeoKeys(geo);
        } else {
            // registered code (e.g. EPSG:3943 Lambert-43) but declared method is
            // "user-defined" (3074=32767): the file carries the full CRS
            // parameters. They are kept; they act as a fallback when the code is
            // not in the registry (Crs.resolve).
            CustomCrs c = customCrsFromGeoKeys(geo);
            Integer ct = c == null ? null : c.key(CustomCrs.KEY_PROJ_COORD_TRANS);
            if (ct != null && ct == CustomCrs.USER_DEFINED) {
                custom = c;
            }
        }
        String noData = geo.entry(TAG_GDAL_NODATA) == null ? null : geo.entry(TAG_GDAL_NODATA).ascii;

        return new CogInfo(levels, crs, custom, originX, originY, scaleX, scaleY, noData);
    }

    /**
     * Sort from the finest to the coarsest by increasing area... careful: the
     * area is computed as a LONG (131072x98304 = 3x2^32 overflows an int and
     * ends up as 0, which placed the overviews before the full resolution -
     * "Missing full resolution IFD" error on large PIX4Dmatic COGs).
     */
    static void sortLevelsByAreaDesc(List<CogInfo.Level> levels) {
        levels.sort((a, b) -> Long.compare((long) b.width * b.height, (long) a.width * a.height));
    }

    private static CogInfo.Level toLevel(Ifd ifd, int index, boolean be) throws IOException {
        if (!ifd.has(TAG_TILE_WIDTH) || !ifd.has(TAG_TILE_LENGTH)
                || !ifd.has(TAG_TILE_OFFSETS) || !ifd.has(TAG_TILE_BYTE_COUNTS)) {
            return null;
        }
        // GDAL COGs with an alpha band interleave a second IFD chain of
        // transparency masks (photometric=4 MinIsMask, NewSubfileType bit 4,
        // often 1-bit DEFLATE): not displayable imagery, and counting them as
        // pyramid levels would double the pyramid and swap RGB for mask at
        // some zooms. They are skipped; alpha compositing is a later phase.
        if ((ifd.getLong(TAG_NEW_SUBFILETYPE, 0) & 4) != 0
                || ifd.getLong(TAG_PHOTOMETRIC, 1) == 4) {
            return null;
        }
        long[] offsets = ifd.getLongs(TAG_TILE_OFFSETS);
        long[] counts = ifd.getLongs(TAG_TILE_BYTE_COUNTS);
        if (offsets == null || counts == null || offsets.length != counts.length) {
            return null;
        }
        int width = (int) ifd.getLong(TAG_IMAGE_WIDTH, 0);
        int height = (int) ifd.getLong(TAG_IMAGE_LENGTH, 0);
        int tw = (int) ifd.getLong(TAG_TILE_WIDTH, 0);
        int th = (int) ifd.getLong(TAG_TILE_LENGTH, 0);
        if (width <= 0 || height <= 0 || tw <= 0 || th <= 0) {
            return null;
        }
        // Hardening: the declared dimensions must be covered by the fetched
        // TileOffsets/TileByteCounts tables, otherwise the tile index of
        // CogInfo.Level escapes the arrays (computed on longs, cannot overflow:
        // numX and numY are each below 2^31, their product below 2^62).
        long numX = ((long) width + tw - 1) / tw;
        long numY = ((long) height + th - 1) / th;
        if (tw > MAX_TILE_DIM || th > MAX_TILE_DIM) {
            throw new IOException("Declared tile size " + tw + "x" + th + " exceeds the maximum supported "
                    + MAX_TILE_DIM + "x" + MAX_TILE_DIM + " (not a COG, or corrupted)");
        }
        if (numX * numY > offsets.length) {
            throw new IOException("TileOffsets/TileByteCounts too short for the declared "
                    + width + "x" + height + " image (" + tw + "x" + th + " tiles): "
                    + offsets.length + " entries for " + numX + "x" + numY + " tiles");
        }
        if (ifd.getLong(TAG_PLANAR_CONFIG, 1) != 1) {
            throw new IOException("PlanarConfiguration=2 not supported");
        }
        int samples = (int) ifd.getLong(TAG_SAMPLES_PER_PIXEL, 1);
        if (samples <= 0 || samples > 64) {
            throw new IOException("Unsupported SamplesPerPixel=" + samples);
        }
        long[] bps = ifd.getLongs(TAG_BITS_PER_SAMPLE);
        int[] bits = new int[samples];
        Arrays.fill(bits, 8);
        if (bps != null) {
            for (int i = 0; i < samples && i < bps.length; i++) {
                bits[i] = (int) bps[i];
            }
        }
        long[] extra = ifd.getLongs(TAG_EXTRA_SAMPLES);
        int[] extras = extra == null ? new int[0] : new int[extra.length];
        if (extra != null) {
            for (int i = 0; i < extra.length; i++) {
                extras[i] = (int) extra[i];
            }
        }
        byte[] jpegTables = readJpegTables(ifd);
        return new CogInfo.Level(
                index, width, height, tw, th,
                (int) ifd.getLong(TAG_COMPRESSION, 1),
                (int) ifd.getLong(TAG_PHOTOMETRIC, 1),
                (int) ifd.getLong(TAG_PREDICTOR, 1),
                samples, bits, extras,
                (int) ifd.getLong(TAG_SAMPLE_FORMAT, 1),
                (int) ifd.getLong(TAG_NEW_SUBFILETYPE, 0),
                be, offsets, counts, jpegTables);
    }

    /** JPEG tables of the JPEGTables tag (347), or null if missing/empty. */
    private static byte[] readJpegTables(Ifd ifd) {
        IfdEntry e = ifd.entry(TAG_JPEG_TABLES);
        if (e == null || e.longs == null || e.longs.length == 0) {
            return null;
        }
        byte[] out = new byte[e.longs.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) e.longs[i];
        }
        return out;
    }

    /** Extracts the EPSG code from the GeoKeys (1024=model type, 3072=projected, 2048=geographic). */
    static int crsFromGeoKeys(Ifd geo) {
        long[] keys = geo.getLongs(TAG_GEO_KEY_DIRECTORY);
        if (keys == null || keys.length < 4) {
            return 0;
        }
        Map<Integer, Integer> values = new HashMap<>();
        // header: Version, KeyRevision, MinorVersion, NumberOfKeys
        int n = (int) keys[3];
        for (int i = 0; i < n && 4 + 4 * i + 3 < keys.length; i++) {
            int key = (int) keys[4 + 4 * i];
            int locator = (int) keys[4 + 4 * i + 1];
            int value = (int) keys[4 + 4 * i + 3];
            if (locator == 0) {
                values.put(key, value);
            }
        }
        int model = values.getOrDefault(1024, 0);
        if (model == 1) {
            return values.getOrDefault(3072, 0);
        }
        if (model == 2) {
            return values.getOrDefault(2048, 0);
        }
        return 0;
    }

    /**
     * Extracts the raw GeoKeys of a "user-defined" CRS (code 32767): SHORT
     * entries of the 34735 directory, DOUBLE parameters of the 34736 tag
     * (referenced by index from the key value field) and the ASCII citation
     * 34737. Returns null when no GeoKeys directory is present.
     */
    static CustomCrs customCrsFromGeoKeys(Ifd geo) {
        long[] keys = geo.getLongs(TAG_GEO_KEY_DIRECTORY);
        if (keys == null || keys.length < 4) {
            return null;
        }
        int n = (int) keys[3];
        List<CustomCrs.Key> out = new ArrayList<>();
        for (int i = 0; i < n && 4 + 4 * i + 3 < keys.length; i++) {
            out.add(new CustomCrs.Key((int) keys[4 + 4 * i], (int) keys[4 + 4 * i + 1],
                    (int) keys[4 + 4 * i + 2], (int) keys[4 + 4 * i + 3]));
        }
        IfdEntry dbl = geo.entry(TAG_GEO_DOUBLE_PARAMS);
        double[] doubles = dbl == null ? new double[0]
                : (dbl.doubles != null ? dbl.doubles : new double[0]);
        IfdEntry ascii = geo.entry(TAG_GEO_ASCII_PARAMS);
        String citation = ascii == null ? null : ascii.ascii;
        return new CustomCrs(out.toArray(new CustomCrs.Key[0]), doubles, citation);
    }

    private static int readU16(byte[] b, int off, boolean be) {
        if (off < 0 || off + 2 > b.length) {
            return 0;
        }
        int hi = b[be ? off : off + 1] & 0xFF;
        int lo = b[be ? off + 1 : off] & 0xFF;
        return (hi << 8) | lo;
    }

    private static int readInt(byte[] b, int off, boolean be) {
        if (off < 0 || off + 4 > b.length) {
            return 0;
        }
        int v = 0;
        for (int i = 0; i < 4; i++) {
            v = (v << 8) | (b[be ? off + i : off + 3 - i] & 0xFF);
        }
        return v;
    }

    private static long readLong(byte[] b, int off, boolean be) {
        if (off < 0 || off + 8 > b.length) {
            return 0;
        }
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[be ? off + i : off + 7 - i] & 0xFFL);
        }
        return v;
    }

    public static String describe(List<Ifd> ifds) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(ifds.size()).append(" IFD(s):\n");
        for (Ifd ifd : ifds) {
            sb.append(String.format("  IFD@a%d: %dx%d comp=%d photo=%d bps=%s%n",
                    ifd.offset,
                    (int) ifd.getLong(TAG_IMAGE_WIDTH, 0), (int) ifd.getLong(TAG_IMAGE_LENGTH, 0),
                    (int) ifd.getLong(TAG_COMPRESSION, 0), (int) ifd.getLong(TAG_PHOTOMETRIC, 0),
                    Arrays.toString(ifd.getLongs(TAG_BITS_PER_SAMPLE))));
        }
        return sb.toString();
    }
}
