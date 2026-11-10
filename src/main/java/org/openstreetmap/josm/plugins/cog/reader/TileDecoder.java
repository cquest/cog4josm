package org.openstreetmap.josm.plugins.cog.reader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/**
 * Decodeur de tuiles TIFF pour les compressions frequentes dans les COG :
 * aucune (1), JPEG (6), deflate (8/32946), LZW (5), PackBits (32773).
 * Supporte Gris, Gris+Alpha, RGB, RGBA, 8 et 16 bits, predictor horizontal.
 * Les echantillons supplementaires (ExtraSamples, masque GDAL) sont traites comme alpha.
 */
public final class TileDecoder {

    private TileDecoder() {
    }

    public static BufferedImage decode(CogInfo.Level level, byte[] raw) throws IOException {
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xD8) == 0xD8) {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
            if (img == null) {
                throw new IOException("Tuile JPEG non decodable (manque peut-etre les tables JPEGQTables)");
            }
            return img;
        }
        byte[] px;
        switch (level.compression) {
            case 1:
                px = raw;
                break;
            case 32773:
                px = packBitsDecode(raw);
                break;
            case 5:
                px = lzwDecode(raw);
                break;
            case 8:
            case 32946:
                px = inflate(raw, false);
                break;
            default:
                throw new IOException("Compression TIFF " + level.compression + " non supportee");
        }
        if (level.predictor == 2) {
            applyHorizontalPredictor(px, level);
        }
        return toImage(px, level);
    }

    // ---------------------------------------------------------------- inflate

    private static byte[] inflate(byte[] raw, boolean rawDeflate) throws IOException {
        Inflater in = new Inflater(rawDeflate);
        in.setInput(raw);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(raw.length * 4, 1024));
        byte[] buf = new byte[16384];
        try {
            while (!in.finished()) {
                int n = in.inflate(buf);
                if (n == 0) {
                    if (in.finished() || in.needsInput() || in.needsDictionary()) {
                        break;
                    }
                }
                out.write(buf, 0, n);
            }
        } catch (DataFormatException e) {
            if (!rawDeflate) {
                // Certains ecriseurs ecrivent du deflate "raw" sans en-tete zlib
                return inflate(raw, true);
            }
            throw new IOException("Deflate invalide", e);
        } finally {
            in.end();
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- packbits

    static byte[] packBitsDecode(byte[] in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length * 2);
        int i = 0;
        while (i < in.length) {
            int n = in[i++];
            if (n >= 0) {
                int count = n + 1;
                if (i + count > in.length) {
                    throw new IOException("PackBits tronque");
                }
                out.write(in, i, count);
                i += count;
            } else if (n > -128) {
                if (i >= in.length) {
                    throw new IOException("PackBits tronque");
                }
                int count = 1 - n;
                for (int k = 0; k < count; k++) {
                    out.write(in[i]);
                }
                i++;
            }
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- lzw

    static byte[] lzwDecode(byte[] in) throws IOException {
        final int clear = 256;
        final int end = 257;
        List<byte[]> dict = newBaseDict();
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length * 4);
        int bits = 9;
        int next = 258;
        int bitPos = 0;
        byte[] prev = null;
        while (true) {
            int code = readBits(in, bitPos, bits);
            bitPos += bits;
            if (code < 0) {
                break;
            }
            if (code == end) {
                break;
            }
            if (code == clear) {
                dict = newBaseDict();
                bits = 9;
                next = 258;
                prev = null;
                continue;
            }
            byte[] entry;
            if (code < dict.size()) {
                entry = dict.get(code);
            } else if (code == next && prev != null) {
                entry = new byte[prev.length + 1];
                System.arraycopy(prev, 0, entry, 0, prev.length);
                entry[prev.length] = prev[0];
            } else {
                throw new IOException("Code LZW invalide : " + code);
            }
            out.writeBytes(entry);
            if (prev != null) {
                byte[] added = new byte[prev.length + 1];
                System.arraycopy(prev, 0, added, 0, prev.length);
                added[prev.length] = entry[0];
                dict.add(added);
                next = dict.size();
                // regle TIFF 6.0 (EarlyChange=0) : le decodeur elargit un code avant l'encodeur
                if (next >= (1 << bits) - 1 && bits < 12) {
                    bits++;
                }
            }
            prev = entry;
        }
        return out.toByteArray();
    }

    private static List<byte[]> newBaseDict() {
        List<byte[]> dict = new ArrayList<>(4096);
        for (int i = 0; i < 256; i++) {
            dict.add(new byte[]{(byte) i});
        }
        dict.add(new byte[0]); // 256 clear
        dict.add(new byte[0]); // 257 end
        return dict;
    }

    private static int readBits(byte[] data, int bitPos, int count) {
        if ((bitPos >> 3) >= data.length) {
            return -1;
        }
        int v = 0;
        int remaining = count;
        int pos = bitPos;
        while (remaining > 0) {
            int byteIdx = pos >> 3;
            if (byteIdx >= data.length) {
                break;
            }
            int bitInByte = 8 - (pos & 7);
            int take = Math.min(remaining, bitInByte);
            int shift = bitInByte - take;
            int mask = (1 << take) - 1;
            int bits = (data[byteIdx] >> shift) & mask;
            v = (v << take) | bits;
            remaining -= take;
            pos += take;
        }
        return v;
    }

    // ---------------------------------------------------------------- predictor

    private static void applyHorizontalPredictor(byte[] px, CogInfo.Level level) throws IOException {
        int b = level.bitsPerSample[0];
        if ((b != 8 && b != 16) || !sameBits(level)) {
            throw new IOException("Predictor horizontal non supporte pour bits=" + b);
        }
        int bytesPerSample = b / 8;
        int stride = level.samplesPerPixel * bytesPerSample;
        int w = level.tileWidth;
        for (int row = 0; row < level.tileHeight; row++) {
            int rowBase = row * w * stride;
            for (int s = 0; s < level.samplesPerPixel; s++) {
                int off = rowBase + s * bytesPerSample;
                if (off + bytesPerSample > px.length) {
                    throw new IOException("Donnees de tuile tronquees");
                }
                int prev = readSample(px, off, b, level.bigEndian);
                for (int x = 1; x < w; x++) {
                    int p = off + x * stride;
                    if (p + bytesPerSample > px.length) {
                        throw new IOException("Donnees de tuile tronquees");
                    }
                    int cur = readSample(px, p, b, level.bigEndian);
                    int sum = (prev + cur) & ((1 << b) - 1);
                    writeSample(px, p, b, sum, level.bigEndian);
                    prev = sum;
                }
            }
        }
    }

    private static boolean sameBits(CogInfo.Level level) {
        for (int v : level.bitsPerSample) {
            if (v != level.bitsPerSample[0]) {
                return false;
            }
        }
        return true;
    }

    private static int readSample(byte[] px, int off, int bits, boolean be) {
        if (bits == 8) {
            return px[off] & 0xFF;
        }
        int hi = px[be ? off : off + 1] & 0xFF;
        int lo = px[be ? off + 1 : off] & 0xFF;
        return (hi << 8) | lo;
    }

    private static void writeSample(byte[] px, int off, int bits, int value, boolean be) {
        if (bits == 8) {
            px[off] = (byte) value;
        } else if (be) {
            px[off] = (byte) (value >> 8);
            px[off + 1] = (byte) value;
        } else {
            px[off] = (byte) value;
            px[off + 1] = (byte) (value >> 8);
        }
    }

    // ---------------------------------------------------------------- raster -> image

    private static BufferedImage toImage(byte[] px, CogInfo.Level level) throws IOException {
        int b = level.bitsPerSample[0];
        int w = level.tileWidth;
        int h = level.tileHeight;
        int spp = level.samplesPerPixel;
        boolean hasAlpha = level.extraSamples.length > 0;
        int bytesPerSample = b / 8;
        long needed = (long) w * h * (spp + (hasAlpha ? 1 : 0)) * bytesPerSample;
        if ((long) px.length < (long) w * h * spp * bytesPerSample) {
            throw new IOException("Taille de tuile attendue " + needed + " octets, recu " + px.length);
        }
        int stride = (spp + (hasAlpha ? 1 : 0)) * bytesPerSample;

        if (b == 16 && spp == 1 && !hasAlpha) {
            if (level.photometric != 1 && level.photometric != 0) {
                throw new IOException("Photometric " + level.photometric + " en 16 bits non supporte");
            }
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY);
            java.awt.image.DataBufferUShort buf =
                    (java.awt.image.DataBufferUShort) ((java.awt.image.WritableRaster) img.getRaster()).getDataBuffer();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int v = readSample(px, (y * w + x) * stride, 16, level.bigEndian);
                    if (level.photometric == 0) {
                        v = 65535 - v;
                    }
                    buf.setElem(y * w + x, v);
                }
            }
            return img;
        }
        if (b != 8) {
            throw new IOException("Profondeur " + b + " bits non supportee");
        }

        switch (level.photometric) {
            case 1:
            case 0: {
                boolean invert = level.photometric == 0;
                if (spp == 1) {
                    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
                    byte[] dst = ((java.awt.image.DataBufferByte)
                            ((java.awt.image.WritableRaster) img.getRaster()).getDataBuffer()).getData();
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            int v = px[(y * w + x) * stride] & 0xFF;
                            dst[y * w + x] = (byte) (invert ? 255 - v : v);
                        }
                    }
                    return img;
                }
                if (spp == 2) {
                    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            int off = (y * w + x) * stride;
                            int v = px[off] & 0xFF;
                            int a = px[off + 1] & 0xFF;
                            img.setRGB(x, y, (a << 24) | ((invert ? 255 - v : v) * 0x00010101));
                        }
                    }
                    return img;
                }
                throw new IOException("Gris avec " + spp + " echantillons non supporte");
            }
            case 2: {
                if (spp == 3 || (spp == 4 && hasAlpha)) {
                    BufferedImage img = new BufferedImage(w, h, hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
                    for (int y = 0; y < h; y++) {
                        for (int x = 0; x < w; x++) {
                            int off = (y * w + x) * stride;
                            int r = px[off] & 0xFF;
                            int g = px[off + 1] & 0xFF;
                            int bl = px[off + 2] & 0xFF;
                            int a = hasAlpha ? px[off + 3] & 0xFF : 255;
                            img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | bl);
                        }
                    }
                    return img;
                }
                throw new IOException("RGB avec " + spp + " echantillons non supporte");
            }
            case 3:
                throw new IOException("TIFF palette (Photometric=3) non supporte");
            case 6:
                throw new IOException("YCbCr brut non supporte (devrait passer par JPEG)");
            default:
                throw new IOException("Photometric " + level.photometric + " non supporte");
        }
    }

}
