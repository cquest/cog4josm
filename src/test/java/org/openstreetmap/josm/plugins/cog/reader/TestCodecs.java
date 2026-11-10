package org.openstreetmap.josm.plugins.cog.reader;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.Deflater;

/** Encodeurs TIFF (LZW EarlyChange=0, PackBits, deflate) pour les tests. */
final class TestCodecs {

    private TestCodecs() {
    }

    static byte[] deflate(byte[] in) {
        Deflater d = new Deflater(6, false);
        d.setInput(in);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) {
            out.write(buf, 0, d.deflate(buf));
        }
        d.end();
        return out.toByteArray();
    }

    static byte[] packBitsEncode(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < in.length) {
            int run = 1;
            while (i + run < in.length && run < 127 && in[i + run] == in[i]) {
                run++;
            }
            if (run >= 3) {
                out.write(1 - run);
                out.write(in[i]);
                i += run;
            } else {
                int start = i;
                int lit = 0;
                while (i < in.length && lit < 128) {
                    if (lit > 0 && i + 2 < in.length && in[i] == in[i + 1] && in[i + 1] == in[i + 2]) {
                        break;
                    }
                    i++;
                    lit++;
                }
                out.write(lit - 1);
                out.write(in, start, lit);
            }
        }
        return out.toByteArray();
    }

    /** Encodeur LZW variante TIFF 6.0 (EarlyChange=0), coherent avec TileDecoder.lzwDecode. */
    static byte[] lzwEncode(byte[] in) {
        BitOut out = new BitOut();
        Map<String, Integer> dict = new HashMap<>();
        int next = 258;
        int width = 9;
        out.write(256, width);
        if (in.length == 0) {
            out.write(257, width);
            return out.bytes();
        }
        String w = String.valueOf((char) (in[0] & 0xFF));
        for (int i = 1; i < in.length; i++) {
            String wk = w + (char) (in[i] & 0xFF);
            if (dict.containsKey(wk)) {
                w = wk;
            } else {
                out.write(code(w, dict), width);
                dict.put(wk, next);
                next++;
                if (next == (1 << width) && width < 12) {
                    width++;
                }
                if (next >= 4096) {
                    out.write(256, width);
                    dict.clear();
                    next = 258;
                    width = 9;
                }
                w = String.valueOf((char) (in[i] & 0xFF));
            }
        }
        out.write(code(w, dict), width);
        out.write(257, width);
        return out.bytes();
    }

    private static int code(String w, Map<String, Integer> dict) {
        if (w.length() == 1) {
            return w.charAt(0) & 0xFF;
        }
        Integer c = dict.get(w);
        if (c == null) {
            throw new IllegalStateException("chaine absente de la table LZW : " + w.length());
        }
        return c;
    }

    /** Ecriture de codes MSB-first dans un flux d'octets. */
    static final class BitOut {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int acc;
        private int count;

        void write(int code, int width) {
            for (int i = width - 1; i >= 0; i--) {
                acc = (acc << 1) | ((code >> i) & 1);
                count++;
                if (count == 8) {
                    bytes.write(acc & 0xFF);
                    acc = 0;
                    count = 0;
                }
            }
        }

        byte[] bytes() {
            if (count > 0) {
                bytes.write((acc << (8 - count)) & 0xFF);
                acc = 0;
                count = 0;
            }
            return bytes.toByteArray();
        }
    }
}
