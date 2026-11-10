package org.openstreetmap.josm.plugins.cog.reader;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Ecrivain de petits TIFF tuiles (COG-like) valides pour les tests. */
final class TestTiffWriter {

    static final int TILE = 16;

    record LevelSpec(int width, int height, int compression, int predictor, int bits, boolean grey) {
    }

    /** Pixel de reference : fonction deterministe des coordonnees image. */
    static int pix(int x, int y, int channel, int bits) {
        return (x * 7 + y * 13 + channel * 31 + 5) & ((1 << bits) - 1);
    }

    static byte[] write(List<LevelSpec> levels, int crs, double scaleX, double originX, double originY)
            throws java.io.IOException {
        int nl = levels.size();

        // 1. tuiles compressees
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

        // 2. positions : IFDs collés apres l'en-tete, puis blocs externes, puis tuiles
        int[] entries = new int[nl];
        for (int i = 0; i < nl; i++) {
            entries[i] = 13 + (i == 0 ? 3 : 0);
        }
        long[] ifdOff = new long[nl];
        long pos = 8;
        for (int i = 0; i < nl; i++) {
            ifdOff[i] = pos;
            pos += 2 + 12L * entries[i] + 4;
        }
        int[] firstTile = new int[nl];
        int t0 = 0;
        for (int i = 0; i < nl; i++) {
            firstTile[i] = t0;
            t0 += perLevel[i];
        }
        long[] extOff = new long[nl];
        for (int i = 0; i < nl; i++) {
            extOff[i] = pos;
            pos += extSize(levels.get(i), perLevel[i]) + (i == 0 ? 96 : 0); // + scale/tiepoint/geokeys
        }
        long[] tileOff = new long[tiles.size()];
        long[] tileCnt = new long[tiles.size()];
        for (int i = 0; i < tiles.size(); i++) {
            tileOff[i] = pos;
            tileCnt[i] = tiles.get(i).length;
            pos += tiles.get(i).length;
        }

        // 3. ecriture sequentielle (les offsets sont deja connus)
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'I', 'I'});
        out.write(leU16(42));
        out.write(leU32(ifdOff[0]));
        for (int i = 0; i < nl; i++) {
            writeIfd(out, levels.get(i), i, ifdOff, extOff[i], perLevel[i], firstTile[i], tileOff, tileCnt);
        }
        for (int i = 0; i < nl; i++) {
            writeExt(out, levels.get(i), i, perLevel[i], firstTile[i], extOff[i], tileOff, tileCnt, scaleX, originX, originY, crs);
        }
        for (byte[] tile : tiles) {
            out.write(tile, 0, tile.length);
        }
        return out.toByteArray();
    }

    private static long extSize(LevelSpec lv, int n) {
        long s = 8L * n; // offsets + counts
        if (!lv.grey()) {
            s += 6; // 3 SHORT bitsPerSample
        }
        return s;
    }

    private static void writeIfd(ByteArrayOutputStream out, LevelSpec lv, int i, long[] ifdOff,
                                 long ext, int n, int firstTile, long[] tileOff, long[] tileCnt)
            throws java.io.IOException {
        long bpsOff = ext + 8L * n;
        long geoBase = bpsOff + (lv.grey() ? 0 : 6);
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
            es.add(e(34735, 3, 12, geoBase + 72));
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
                                 double scaleX, double originX, double originY, int crs)
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
        if (i == 0) {
            writeDoubles(out, new double[]{scaleX, scaleX, 0});
            writeDoubles(out, new double[]{0, 0, 0, originX, originY, 0});
            boolean geographic = crs == 4326;
            short[] keys = {1, 1, 0, 2, // Version=1, KeyRevision=1, MinorVersion=0, NumberOfKeys=2
                    1024, 0, 1, (short) (geographic ? 2 : 1),
                    (short) (geographic ? 2048 : 3072), 0, 1, (short) crs};
            for (short k : keys) {
                out.write(leU16(k));
            }
        }
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
                    int v = pix(gx, gy, s, lv.bits());
                    if (lv.predictor() == 2) {
                        int left = x == 0 ? 0 : pix(gx - 1, gy, s, lv.bits());
                        v = (v - left) & ((1 << lv.bits()) - 1);
                    }
                    if (bytes == 2) {
                        // fichier little-endian ('II') : octet de poids faible d'abord
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
            default: throw new IllegalArgumentException("compression inconnue " + lv.compression());
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
