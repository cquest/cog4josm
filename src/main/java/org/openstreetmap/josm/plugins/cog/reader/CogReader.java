package org.openstreetmap.josm.plugins.cog.reader;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.awt.image.BufferedImage;

/**
 * Entry point: opens a COG through a {@link ByteSource}, parses the headers
 * (metadata reads served by the global {@link MetadataCache} for HTTP
 * sources), then provides decoded tiles with an in-memory LRU cache.
 *
 * <p>Tiles of one request window are fetched through
 * {@link RangePlanner}-merged byte-ranges: COG tiles are stored row-major and
 * are usually contiguous, so adjacent tiles of a window cost one HTTP request
 * per block instead of one per tile. Raw raster bytes are never cached —
 * only the decoded images are.</p>
 */
public final class CogReader implements Closeable {

    private static final int MAX_CACHED_TILES = 128;
    /** Maximum hole between two tile ranges to still fetch them together. */
    private static final int FETCH_MAX_GAP = 512;
    /** Preferred cap of one merged tile fetch (bigger tiles keep their own request). */
    private static final int FETCH_MAX_BLOCK = 1 << 20;

    private final ByteSource source;
    private final CogInfo info;
    private final Map<String, BufferedImage> tileCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > MAX_CACHED_TILES;
                }
            });

    public static CogReader open(String url) throws IOException {
        return open(url, Map.of());
    }

    public static CogReader open(String url, Map<String, String> headers) throws IOException {
        HttpRangeReader http = HttpRangeReader.open(url, headers);
        if (!http.supportsRanges()) {
            http.close();
            throw new RangeNotSupportedException(url);
        }
        MetadataCache.global().validate(url, http.size(), http.etag());
        return new CogReader(http, TiffParser.parse(new MetadataCachingSource(http)));
    }

    public static CogReader open(ByteSource source) throws IOException {
        return new CogReader(source, TiffParser.parse(source));
    }

    private CogReader(ByteSource source, CogInfo info) {
        this.source = source;
        this.info = info;
    }

    public CogInfo info() {
        return info;
    }

    public ByteSource source() {
        return source;
    }

    /** Key of the map returned by {@link #getTiles}: packs a tile column and row. */
    public static long tileKey(int tx, int ty) {
        return ((long) ty << 32) | (tx & 0xffffffffL);
    }

    /** Decoded tile of level {@code level} at tile position (tx, ty). */
    public BufferedImage getTile(int level, int tx, int ty) throws IOException {
        CogInfo.Level lvl = info.level(level);
        if (!lvl.hasTile(tx, ty)) {
            throw new IOException("Tile outside the pyramid: level " + level + " tile " + tx + "," + ty
                    + " (" + lvl.numXTiles() + "x" + lvl.numYTiles() + ")");
        }
        return getTiles(level, tx, tx, ty, ty).get(tileKey(tx, ty));
    }

    /**
     * Decoded tiles of the inclusive rectangle [tx0..tx1] x [ty0..ty1] of one
     * level, missing ones fetched with merged byte-range requests. Tiles off
     * the pyramid are skipped; results are keyed by {@link #tileKey}.
     */
    public Map<Long, BufferedImage> getTiles(int level, int tx0, int tx1, int ty0, int ty1)
            throws IOException {
        CogInfo.Level lvl = info.level(level);
        Map<Long, BufferedImage> out = new LinkedHashMap<>();
        List<long[]> ranges = new ArrayList<>();
        List<int[]> coords = new ArrayList<>();
        for (int ty = Math.max(0, ty0); ty <= ty1; ty++) {
            for (int tx = Math.max(0, tx0); tx <= tx1; tx++) {
                if (!lvl.hasTile(tx, ty)) {
                    continue;
                }
                long key = tileKey(tx, ty);
                if (out.containsKey(key)) {
                    continue;
                }
                BufferedImage cached = tileCache.get(level + "/" + tx + "/" + ty);
                if (cached != null) {
                    out.put(key, cached);
                    continue;
                }
                ranges.add(new long[]{lvl.tileOffset(tx, ty), lvl.tileByteCount(tx, ty)});
                coords.add(new int[]{tx, ty});
            }
        }
        if (!ranges.isEmpty()) {
            for (RangePlanner.Block b : RangePlanner.plan(ranges, FETCH_MAX_GAP, FETCH_MAX_BLOCK)) {
                byte[] data = source.read(b.offset, b.length);
                for (int idx : b.members) {
                    long[] r = ranges.get(idx);
                    int[] c = coords.get(idx);
                    int from = (int) (r[0] - b.offset);
                    // r[1] is attacker-controlled: keep the copy within the data
                    // (a byte count beyond 2 GB would overflow the int cast)
                    int to = (int) Math.min((long) data.length, (long) from + r[1]);
                    byte[] raw = Arrays.copyOfRange(data, from, to);
                    BufferedImage img = TileDecoder.decode(lvl, raw);
                    tileCache.put(level + "/" + c[0] + "/" + c[1], img);
                    out.put(tileKey(c[0], c[1]), img);
                }
            }
        }
        return out;
    }

    public void clearCache() {
        tileCache.clear();
    }

    @Override
    public void close() throws IOException {
        tileCache.clear();
        source.close();
    }
}
