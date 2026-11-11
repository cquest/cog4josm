package org.openstreetmap.josm.plugins.cog.reader;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Writer for small tiled TIFFs (COG-like) that are valid for tests.
 * Public: shared with tests of other packages (layer/).
 */
public final class TestTiffWriter {

    static final int TILE = 16;

    public record LevelSpec(int width, int height, int compression, int predictor, int bits, boolean grey,
                            boolean checker, String noData) {

        public LevelSpec(int width, int height, int compression, int predictor, int bits, boolean grey) {
            this(width, height, compression, predictor, bits, grey, false, null);
        }

        public LevelSpec {
            if (checker && (grey || bits != 8 || predictor != 1 || compression != 1)) {
                throw new IllegalArgumentException("checker content needs uncompressed 8-bit RGB");
            }
        }
    }

    /** Checker content: opaque red next to black (the phase 3 fixture). */
    private static final int[] CHECKER_RGB = {210, 30, 40};

    /** Reference pixel: deterministic function of image coordinates. */
    static int pix(int x, int y, int channel, int bits) {
        return (x * 7 + y * 13 + channel * 31 + 5) & ((1 << bits) - 1);
    }

    public static byte[] write(List<LevelSpec> levels, int crs, double scaleX, double originX, double originY)
            throws java.io.IOException {
        return write(levels, crs, null, null, scaleX, originX, originY);
    }

    /**
     * Header-tail layout (GDAL COG driver style): tile data first, then the
     * external arrays and the IFDs at the very end of the file — the first
     * IFD is near the tail, far outside a 32 KiB head read.
     */
    public static byte[] writeTail(List<LevelSpec> levels, int crs, double scaleX, double originX, double originY)
            throws java.io.IOException {
        return write(levels, crs, null, null, scaleX, originX, originY, true);
    }

    /**
     * Variant with "user-defined" GeoKeys: {@code customKeys} is the complete
     * content of the 34735 directory (4-short header + entries), {@code customDoubles}
     * the DOUBLE array of tag 34736 (may be null/empty).
     */
    public static byte[] write(List<LevelSpec> levels, int crs, int[] customKeys, double[] customDoubles,
                        double scaleX, double originX, double originY) throws java.io.IOException {
        return write(levels, crs, customKeys, customDoubles, scaleX, originX, originY, false);
    }

    private static byte[] write(List<LevelSpec> levels, int crs, int[] customKeys, double[] customDoubles,
                        double scaleX, double originX, double originY, boolean tailLayout)
            throws java.io.IOException {
        int nl = levels.size();
        int nCustomKeys = customKeys == null ? 0 : customKeys.length;
        int nCustomDbl = customDoubles == null ? 0 : customDoubles.length;
        if (nCustomKeys > 0) {
            if (nCustomKeys % 4 != 0 || customKeys[3] != (nCustomKeys - 4) / 4) {
                throw new IllegalArgumentException("inconsistent GeoKeys directory: "
                        + nCustomKeys + " shorts, header NumberOfKeys=" + customKeys[3]);
            }
            for (int i = 0; i < nCustomKeys; i++) {
                if (customKeys[i] < 0 || customKeys[i] > 65535) {
                    throw new IllegalArgumentException("GeoKey value outside unsigned short: " + customKeys[i]);
                }
            }
        }

        // 1. compressed tiles
        List<byte[]> tiles = new ArrayList<>();
        int[] perLevel = new int[nl];
        for (int i = 0; i < nl; i++) {
            LevelSpec lv = levels.get(i);
            int ntx = (lv.width() + TILE - 1) / TILE;
            int nty = (lv.height() + TILE - 1) / TILE;
            perLevel[i] = ntx * nty;
            for (int ty = 0; ty < nty; ty++) {
                for (int tx = 0; tx < ntx; tx++) {
                    tiles.add(buildTile(lv, tx, ty));
                }
            }
        }

        // 2. layout: head = IFDs right after the header, then external blocks,
        // then tiles; tail (header-tail COG) = tiles, then external blocks,
        // then the IFDs at the very end
        int[] entries = new int[nl];
        for (int i = 0; i < nl; i++) {
            entries[i] = 13 + (i == 0
                    ? 3 + (nCustomKeys > 0 ? 2 : 0) + (levels.get(0).noData() != null ? 1 : 0)
                    : 0);
        }
        long[] ifdOff = new long[nl];
        long[] extOff = new long[nl];
        long[] tileOff = new long[tiles.size()];
        long[] tileCnt = new long[tiles.size()];
        int[] firstTile = new int[nl];
        int t0 = 0;
        for (int i = 0; i < nl; i++) {
            firstTile[i] = t0;
            t0 += perLevel[i];
        }
        long pos = 8;
        if (tailLayout) {
            for (int i = 0; i < tiles.size(); i++) {
                tileOff[i] = pos;
                tileCnt[i] = tiles.get(i).length;
                pos += tiles.get(i).length;
            }
            for (int i = 0; i < nl; i++) {
                extOff[i] = pos;
                pos += extSize(levels.get(i), perLevel[i]) + (i == 0 ? geoBlockSize(nCustomKeys, nCustomDbl) : 0);
            }
            for (int i = 0; i < nl; i++) {
                ifdOff[i] = pos;
                pos += 2 + 12L * entries[i] + 4;
            }
        } else {
            for (int i = 0; i < nl; i++) {
                ifdOff[i] = pos;
                pos += 2 + 12L * entries[i] + 4;
            }
            for (int i = 0; i < nl; i++) {
                extOff[i] = pos;
                pos += extSize(levels.get(i), perLevel[i]) + (i == 0 ? geoBlockSize(nCustomKeys, nCustomDbl) : 0);
            }
            for (int i = 0; i < tiles.size(); i++) {
                tileOff[i] = pos;
                tileCnt[i] = tiles.get(i).length;
                pos += tiles.get(i).length;
            }
        }

        // 3. sequential write (the offsets are already known)
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'I', 'I'});
        out.write(leU16(42));
        out.write(leU32(ifdOff[0]));
        if (tailLayout) {
            for (byte[] tile : tiles) {
                out.write(tile, 0, tile.length);
            }
            for (int i = 0; i < nl; i++) {
                writeExt(out, levels.get(i), i, perLevel[i], firstTile[i], extOff[i], tileOff, tileCnt,
                        scaleX, originX, originY, crs, customKeys, customDoubles);
            }
            for (int i = 0; i < nl; i++) {
                writeIfd(out, levels.get(i), i, ifdOff, extOff[i], perLevel[i], firstTile[i], tileOff, tileCnt,
                        crs, nCustomKeys, nCustomDbl);
            }
        } else {
            for (int i = 0; i < nl; i++) {
                writeIfd(out, levels.get(i), i, ifdOff, extOff[i], perLevel[i], firstTile[i], tileOff, tileCnt,
                        crs, nCustomKeys, nCustomDbl);
            }
            for (int i = 0; i < nl; i++) {
                writeExt(out, levels.get(i), i, perLevel[i], firstTile[i], extOff[i], tileOff, tileCnt,
                        scaleX, originX, originY, crs, customKeys, customDoubles);
            }
            for (byte[] tile : tiles) {
                out.write(tile, 0, tile.length);
            }
        }
        return out.toByteArray();
    }

    private static long extSize(LevelSpec lv, int n) {
        long s = 8L * n; // offsets + counts
        if (!lv.grey()) {
            s += 6; // 3 SHORT bitsPerSample
        }
        if (lv.noData() != null) {
            s += lv.noData().length() + 1; // ASCII 42113 with its NUL
        }
        return s;
    }

    /** Size of the geographic block (scale + tiepoint + geokeys), exactly what writeExt writes. */
    private static long geoBlockSize(int nCustomKeys, int nCustomDbl) {
        if (nCustomKeys <= 0) {
            return 96; // scale(24) + tiepoint(48) + 12 short keys(24)
        }
        return 72 + 2L * nCustomKeys + 8L * nCustomDbl + CITATION.length() + 1;
    }

    private static void writeIfd(ByteArrayOutputStream out, LevelSpec lv, int i, long[] ifdOff,
                                 long ext, int n, int firstTile, long[] tileOff, long[] tileCnt,
                                 int crs, int nCustomKeys, int nCustomDbl)
            throws java.io.IOException {
        // the custom block (34736/34737) depends on the presence of the keys, not
        // on the crs value: a "hybrid" case (registered code + parameters, cf.
        // PIX4Dmatic) must keep exactly the same byte layout
        boolean custom = i == 0 && nCustomKeys > 0;
        long bpsOff = ext + 8L * n;
        long noDataOff = bpsOff + (lv.grey() ? 0 : 6);
        long geoBase = noDataOff + (i == 0 && lv.noData() != null ? lv.noData().length() + 1 : 0);
        List<long[]> es = new ArrayList<>();
        es.add(e(254, 3, 1, i == 0 ? 0 : 1));
        es.add(e(256, 3, 1, lv.width()));
        es.add(e(257, 3, 1, lv.height()));
        if (lv.grey()) {
            es.add(e(258, 3, 1, lv.bits()));
        } else {
            es.add(e(258, 3, 3, bpsOff));
        }
        es.add(e(259, 3, 1, lv.compression()));
        es.add(e(262, 3, 1, lv.grey() ? 1 : 2));
        es.add(e(277, 3, 1, lv.grey() ? 1 : 3));
        es.add(e(284, 3, 1, 1));
        es.add(e(317, 3, 1, lv.predictor()));
        es.add(e(322, 3, 1, TILE));
        es.add(e(323, 3, 1, TILE));
        es.add(e(324, 4, n, ext));
        es.add(e(325, 4, n, ext + 4L * n));
        if (i == 0) {
            es.add(e(33550, 12, 3, geoBase));
            es.add(e(33922, 12, 6, geoBase + 24));
            es.add(e(34735, 3, nCustomKeys > 0 ? nCustomKeys : 12, geoBase + 72));
            if (custom) {
                es.add(e(34736, 12, nCustomDbl, geoBase + 72 + 2L * nCustomKeys));
                es.add(e(34737, 2, CITATION.length(), geoBase + 72 + 2L * nCustomKeys + 8L * nCustomDbl));
            }
            if (lv.noData() != null) {
                // GDAL_NODATA (42113): ASCII, count includes the NUL
                es.add(e(42113, 2, lv.noData().length() + 1, noDataOff));
            }
        }
        out.write(leU16(es.size()));
        for (long[] en : es) {
            out.write(leU16((int) en[0]));
            out.write(leU16((int) en[1]));
            out.write(leU32(en[2]));
            out.write(leU32(en[3]));
        }
        out.write(leU32(i + 1 < ifdOff.length ? ifdOff[i + 1] : 0));
    }

    private static void writeExt(ByteArrayOutputStream out, LevelSpec lv, int i, int n, int firstTile,
                                 long ext, long[] tileOff, long[] tileCnt,
                                 double scaleX, double originX, double originY, int crs,
                                 int[] customKeys, double[] customDoubles)
            throws java.io.IOException {
        for (int t = 0; t < n; t++) {
            out.write(leU32(tileOff[firstTile + t]));
        }
        for (int t = 0; t < n; t++) {
            out.write(leU32(tileCnt[firstTile + t]));
        }
        if (!lv.grey()) {
            for (int s = 0; s < 3; s++) {
                out.write(leU16(lv.bits()));
            }
        }
        if (i == 0 && lv.noData() != null) {
            for (byte b : lv.noData().getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
                out.write(b);
            }
            out.write(0);
        }
        if (i == 0) {
            writeDoubles(out, new double[]{scaleX, scaleX, 0});
            writeDoubles(out, new double[]{0, 0, 0, originX, originY, 0});
            if (customKeys != null) {
                for (int k : customKeys) {
                    out.write(leU16(k));
                }
                writeDoubles(out, customDoubles);
                for (byte b : CITATION.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) {
                    out.write(b);
                }
                out.write(0);
            } else {
                boolean geographic = crs == 4326;
                short[] keys = {1, 1, 0, 2, // Version=1, KeyRevision=1, MinorVersion=0, NumberOfKeys=2
                        1024, 0, 1, (short) (geographic ? 2 : 1),
                        (short) (geographic ? 2048 : 3072), 0, 1, (short) crs};
                for (short k : keys) {
                    out.write(leU16(k));
                }
            }
        }
    }

    private static final String CITATION = "josm-cog test citation";

    /**
     * GeoKeys directory of a "user-defined" Lambert-93 projected system
     * (codes 32767), written the way GDAL >= 3 would produce it: DOUBLE keys
     * referenced in tag 34736. Returns {keys, doubles, originAnchorOk}.
     */
    public static int[] customKeysLcc(double[] doublesOutHolder) {
        return customKeysLcc(doublesOutHolder, 32767, 32767);
    }

    /**
     * Same directory but 3072/2048 carry registered codes: "hybrid" variant
     * exported by PIX4Dmatic (e.g. EPSG:3943 + 3074=32767 and all parameters
     * as DOUBLE, cf. COG_BayonneSudEst.tif).
     */
    static int[] customKeysLcc(double[] doublesOutHolder, int pcsCode, int gcsCode) {
        double[] d = {46.5, 3.0, 44.0, 49.0, 700000.0, 6600000.0, 298.257222101, 6378137.0, 0.0};
        if (doublesOutHolder != null && doublesOutHolder.length >= d.length) {
            System.arraycopy(d, 0, doublesOutHolder, 0, d.length);
        }
        return new int[]{
                1, 1, 0, 17, // header: 17 keys
                1024, 0, 1, 1,       // ModelTypeGeoKey = Projected
                2048, 0, 1, gcsCode, // GCS (user-defined or registered code)
                2054, 0, 1, 9102, // angular units: degrees
                2056, 0, 1, 7019, // GRS 1980 ellipsoid
                2057, 34736, 1, 7,   // semi-major axis -> dbl[7]
                2059, 34736, 1, 6,   // inverse flattening -> dbl[6]
                2061, 34736, 1, 8,   // prime meridian -> dbl[8]
                3072, 0, 1, pcsCode, // PCS (user-defined or registered code)
                3074, 0, 1, 32767, // ProjCoordTrans user-defined
                3075, 0, 1, 8,     // method = Lambert Conf Conic 2SP
                3076, 0, 1, 9001,  // linear units: metre
                3078, 34736, 1, 2,   // standard parallel 1 -> dbl[2]
                3079, 34736, 1, 3,   // standard parallel 2 -> dbl[3]
                3084, 34736, 1, 1,   // false origin longitude -> dbl[1]
                3085, 34736, 1, 0,   // false origin latitude -> dbl[0]
                3086, 34736, 1, 4,   // false easting -> dbl[4]
                3087, 34736, 1, 5,   // false northing -> dbl[5]
        };
    }

    private static byte[] buildTile(LevelSpec lv, int tx, int ty) {
        int bytes = lv.bits() / 8;
        int samples = lv.grey() ? 1 : 3;
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                int gx = tx * TILE + x;
                int gy = ty * TILE + y;
                for (int s = 0; s < samples; s++) {
                    int v;
                    if (lv.checker()) {
                        // 4 px blocks: black vs red (black-to-alpha fixture)
                        v = (((gx >> 2) + (gy >> 2)) & 1) == 0 ? 0 : CHECKER_RGB[s];
                    } else {
                        v = pix(gx, gy, s, lv.bits());
                        if (lv.predictor() == 2) {
                            int left = x == 0 ? 0 : pix(gx - 1, gy, s, lv.bits());
                            v = (v - left) & ((1 << lv.bits()) - 1);
                        }
                    }
                    if (bytes == 2) {
                        // little-endian file ('II'): least significant byte first
                        raw.write(v & 0xFF);
                        raw.write((v >> 8) & 0xFF);
                    } else {
                        raw.write(v);
                    }
                }
            }
        }
        byte[] bytesArr = raw.toByteArray();
        switch (lv.compression()) {
            case 1: return bytesArr;
            case 8: case 32946: return TestCodecs.deflate(bytesArr);
            case 32773: return TestCodecs.packBitsEncode(bytesArr);
            case 5: return TestCodecs.lzwEncode(bytesArr);
            default: throw new IllegalArgumentException("unknown compression " + lv.compression());
        }
    }

    private static long[] e(long tag, long type, long count, long value) {
        return new long[]{tag, type, count, value};
    }

    private static void writeDoubles(ByteArrayOutputStream out, double[] d) {
        for (double v : d) {
            long bits = Double.doubleToLongBits(v);
            for (int i = 0; i < 8; i++) {
                out.write((int) (bits >> (8 * i)) & 0xFF);
            }
        }
    }

    private static byte[] leU16(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8)};
    }

    private static byte[] leU32(long v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }
}
