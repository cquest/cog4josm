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
 * TIFF tile decoder for the compression schemes often found in COGs:
 * none (1), JPEG (6), deflate (8/32946), LZW (5), PackBits (32773).
 * Supports Grey, Grey+Alpha, RGB, RGBA, 8 and 16 bits, horizontal predictor.
 * Extra samples (ExtraSamples, GDAL mask) are handled as alpha.
 */
public final class TileDecoder {

    private TileDecoder() {
    }

    public static BufferedImage decode(CogInfo.Level level, byte[] raw) throws IOException {
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xD8) == 0xD8) {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(withJpegTables(raw, level.jpegTables)));
            if (img == null) {
                throw new IOException("Cannot decode JPEG tile (the JPEGTables may be missing)");
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
                throw new IOException("TIFF compression " + level.compression + " not supported");
        }
        if (level.predictor == 2) {
            applyHorizontalPredictor(px, level);
        }
        return toImage(px, level);
    }

    // ---------------------------------------------------------------- JPEG tables

    /**
     * Some TIFF writers (GDAL, COG driver) store the JPEG tables
     * (DQT quantization...) in the JPEGTables tag (347) instead of embedding
     * them in every tile: the tile stream starts with SOI then SOF without a
     * DQT, and ImageIO refuses to decode it. Rebuilds a valid JPEG stream by
     * inserting the tables after the SOI of the tag.
     */
    static byte[] withJpegTables(byte[] raw, byte[] tables) {
        if (tables == null || tables.length < 4 || (tables[0] & 0xFF) != 0xFF || (tables[1] & 0xD8) != 0xD8) {
            return raw;
        }
        // scan segments up to SOS: if a DQT is already present, nothing to do
        int i = 2;
        while (i + 1 < raw.length && (raw[i] & 0xFF) == 0xFF) {
            int m = raw[i + 1] & 0xFF;
            if (m == 0xDB) {
                return raw;
            }
            if (m == 0xDA || m == 0xD9) {
                break;
            }
            if (m == 0x01 || (m >= 0xD0 && m <= 0xD7) || m == 0xD8) {
                i += 2;
                continue;
            }
            if (i + 3 >= raw.length) {
                break;
            }
            int len = ((raw[i + 2] & 0xFF) << 8) | (raw[i + 3] & 0xFF);
            if (len < 2) {
                break;
            }
            i += 2 + len;
        }
        // tables block = SOI + segments, without the possible trailing EOI
        int tlen = tables.length;
        if (tlen >= 2 && (tables[tlen - 2] & 0xFF) == 0xFF && (tables[tlen - 1] & 0xFF) == 0xD9) {
            tlen -= 2;
        }
        byte[] out = new byte[tlen + raw.length - 2];
        System.arraycopy(tables, 0, out, 0, tlen);
        System.arraycopy(raw, 2, out, tlen, raw.length - 2);
        return out;
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
                // some writers produce "raw" deflate without a zlib header
                return inflate(raw, true);
            }
            throw new IOException("Invalid deflate", e);
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
                    throw new IOException("Truncated PackBits");
                }
                out.write(in, i, count);
                i += count;
            } else if (n > -128) {
                if (i >= in.length) {
                    throw new IOException("Truncated PackBits");
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
                throw new IOException("Invalid LZW code: " + code);
            }
            out.writeBytes(entry);
            if (prev != null) {
                byte[] added = new byte[prev.length + 1];
                System.arraycopy(prev, 0, added, 0, prev.length);
                added[prev.length] = entry[0];
                dict.add(added);
                next = dict.size();
                // TIFF 6.0 rule (EarlyChange=0): the decoder widens a code before the encoder
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
            throw new IOException("Horizontal predictor not supported for bits=" + b);
        }
        int bytesPerSample = b / 8;
        int stride = level.samplesPerPixel * bytesPerSample;
        int w = level.tileWidth;
        for (int row = 0; row < level.tileHeight; row++) {
            int rowBase = row * w * stride;
            for (int s = 0; s < level.samplesPerPixel; s++) {
                int off = rowBase + s * bytesPerSample;
                if (off + bytesPerSample > px.length) {
                    throw new IOException("Truncated tile data");
                }
                int prev = readSample(px, off, b, level.bigEndian);
                for (int x = 1; x < w; x++) {
                    int p = off + x * stride;
                    if (p + bytesPerSample > px.length) {
                        throw new IOException("Truncated tile data");
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
            throw new IOException("Expected tile size " + needed + " bytes, received " + px.length);
        }
        int stride = (spp + (hasAlpha ? 1 : 0)) * bytesPerSample;

        if (b == 16 && spp == 1 && !hasAlpha) {
            if (level.photometric != 1 && level.photometric != 0) {
                throw new IOException("Photometric " + level.photometric + " in 16 bits not supported");
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
            throw new IOException("Bit depth " + b + " not supported");
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
                throw new IOException("Grey with " + spp + " samples not supported");
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
                throw new IOException("RGB with " + spp + " samples not supported");
            }
            case 3:
                throw new IOException("TIFF palette (Photometric=3) not supported");
            case 6:
                throw new IOException("Raw YCbCr not supported (should go through JPEG)");
            default:
                throw new IOException("Photometric " + level.photometric + " not supported");
        }
    }

}
