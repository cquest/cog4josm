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
 * sources, pre-seeded by one speculative head read), then provides decoded
 * tiles with an in-memory LRU cache.
 *
 * <p>Tiles of one request window are fetched through
 * {@link RangePlanner}-merged byte-ranges: COG tiles are stored row-major and
 * are usually contiguous, so adjacent tiles of a window cost one HTTP request
 * per block instead of one per tile. The window is padded by a speculative
 * ring of one tile row/column (fetched and LRU-decoded, not returned): panning
 * to the neighbouring display tiles usually costs no request at all. Raw
 * raster bytes are never cached — only the decoded images are. When the
 * plugin attaches a {@link FileMetadataStore}, metadata also survives JOSM
 * restarts: reopening a COG is one conditional request (304, no body).</p>
 */
public final class CogReader implements Closeable {

    private static final int MAX_CACHED_TILES = 256;
    /** Maximum hole between two tile ranges to still fetch them together. */
    private static final int FETCH_MAX_GAP = 4096;
    /** Preferred cap of one merged tile fetch (bigger tiles keep their own request). */
    private static final int FETCH_MAX_BLOCK = 1 << 20;
    /**
     * Speculative ring: every tile fetch window is padded by one extra row
     * and column of source tiles (phase 2.7 E1): the padded tiles are
     * fetched in the same merged blocks and decoded into the tile LRU, so
     * the neighbouring display tiles of the same level usually cost zero
     * request. They are NOT part of the returned map.
     */
    private static final int RING_TILES = 1;
    /** The ring is skipped when it would decode more than this many extra tiles... */
    private static final int MAX_RING_TILES = 48;
    /** ...or fetch more than this many raw bytes (guard against huge windows). */
    private static final long MAX_RING_BYTES = 8L << 20;
    /** Suggested cap for {@link #prefetchBaseOverview(long)} (phase 2.7 E2). */
    public static final long OVERVIEW_PREFETCH_MAX_BYTES = 2L << 20;
    /**
     * Speculative head read done when opening an HTTP source: one request
     * brings the header, IFDs, GeoKeys and tile-offset tables of a
     * normatively laid-out COG (they all sit at the head of the file), so
     * parsing the metadata usually costs this single round-trip. It also
     * replaces the old one-byte range probe: the 206 answer reveals the
     * total size and the ETag, a 200 answer proves ranges are unsupported.
     * Header-tail COGs (GDAL driver default) store those structures at the
     * very end instead: {@link MetadataCachingSource} then fetches one
     * speculative tail block of the same size, so the open costs two
     * requests in every layout.
     */
    static final int HEAD_READ_BYTES = 1 << 15;

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
        try {
            MetadataCache cache = MetadataCache.global();
            MetadataCache.Identity known = cache.knownIdentity(url);
            byte[] head;
            if (known != null) {
                // phase 2.8: a persisted (size, ETag) fingerprint lets the
                // head read be conditional — a 304 answer costs zero body
                // bytes and the metadata comes from memory or the store
                head = http.conditionalHeadRead(HEAD_READ_BYTES, known.size(), known.etag());
            } else {
                // one speculative head read (no probe): the 206 answer validates
                // range support and reveals size/ETag, a 200 answer throws
                // RangeNotSupportedException before anything big is buffered
                head = http.read(0, HEAD_READ_BYTES);
            }
            cache.validate(url, http.size(), http.etag());
            if (head != null) {
                // seed the metadata cache: parsing below slices this block
                // whenever it covers the requested ranges
                cache.store(url, 0, head);
            }
            CogInfo info = TiffParser.parse(new MetadataCachingSource(http));
            // keep the store warm for the next session (no-op without store):
            // done after the parse so the speculative tail block fetched by
            // MetadataCachingSource (header-tail COGs) is persisted too
            cache.mirrorToStore(url);
            return new CogReader(http, info);
        } catch (IOException | RuntimeException e) {
            http.close();
            throw e;
        }
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
     * the pyramid are skipped; results are keyed by {@link #tileKey}. The
     * fetch is padded by a speculative ring of one tile (see {@code
     * RING_TILES}): ring tiles are decoded into the tile LRU only.
     */
    public Map<Long, BufferedImage> getTiles(int level, int tx0, int tx1, int ty0, int ty1)
            throws IOException {
        CogInfo.Level lvl = info.level(level);
        Map<Long, BufferedImage> out = new LinkedHashMap<>();
        int rx0 = Math.max(0, tx0);
        int ry0 = Math.max(0, ty0);
        int rx1 = Math.min(lvl.numXTiles() - 1, tx1);
        int ry1 = Math.min(lvl.numYTiles() - 1, ty1);
        if (rx0 > rx1 || ry0 > ry1) {
            return out;
        }
        boolean requestedMiss = false;
        scan:
        for (int ty = ry0; ty <= ry1; ty++) {
            for (int tx = rx0; tx <= rx1; tx++) {
                if (tileCache.get(level + "/" + tx + "/" + ty) == null) {
                    requestedMiss = true;
                    break scan;
                }
            }
        }
        int fx0 = rx0;
        int fx1 = rx1;
        int fy0 = ry0;
        int fy1 = ry1;
        // the ring is only speculative when something is actually fetched:
        // a pan fully covered by the previous ring costs zero request and
        // does not chase the horizon further
        int[] ring = requestedMiss ? ringBounds(level, lvl, rx0, rx1, ry0, ry1) : null;
        if (ring != null) {
            fx0 = ring[0];
            fx1 = ring[1];
            fy0 = ring[2];
            fy1 = ring[3];
        }
        List<long[]> ranges = new ArrayList<>();
        List<int[]> coords = new ArrayList<>();
        for (int ty = fy0; ty <= fy1; ty++) {
            for (int tx = fx0; tx <= fx1; tx++) {
                BufferedImage cached = tileCache.get(level + "/" + tx + "/" + ty);
                if (cached != null) {
                    if (tx >= rx0 && tx <= rx1 && ty >= ry0 && ty <= ry1) {
                        out.put(tileKey(tx, ty), cached);
                    }
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
                    if (c[0] >= rx0 && c[0] <= rx1 && c[1] >= ry0 && c[1] <= ry1) {
                        out.put(tileKey(c[0], c[1]), img);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Expanded fetch rectangle including the speculative ring, or null when
     * the ring must be skipped: it would decode more than {@code
     * MAX_RING_TILES} uncached extra tiles or fetch more than {@code
     * MAX_RING_BYTES} (huge warp windows, coarse grids where the ring
     * covers everything anyway).
     */
    private int[] ringBounds(int level, CogInfo.Level lvl, int rx0, int rx1, int ry0, int ry1) {
        int cx0 = Math.max(0, rx0 - RING_TILES);
        int cx1 = Math.min(lvl.numXTiles() - 1, rx1 + RING_TILES);
        int cy0 = Math.max(0, ry0 - RING_TILES);
        int cy1 = Math.min(lvl.numYTiles() - 1, ry1 + RING_TILES);
        if (cx0 == rx0 && cx1 == rx1 && cy0 == ry0 && cy1 == ry1) {
            return null;
        }
        int extraTiles = (cx1 - cx0 + 1) * (cy1 - cy0 + 1) - (rx1 - rx0 + 1) * (ry1 - ry0 + 1);
        if (extraTiles > MAX_RING_TILES) {
            return null;
        }
        long extraBytes = 0;
        for (int ty = cy0; ty <= cy1; ty++) {
            for (int tx = cx0; tx <= cx1; tx++) {
                if ((tx < rx0 || tx > rx1 || ty < ry0 || ty > ry1)
                        && tileCache.get(level + "/" + tx + "/" + ty) == null) {
                    long bc = Math.min(Math.max(0, lvl.tileByteCount(tx, ty)), MAX_RING_BYTES + 1);
                    extraBytes += bc;
                    if (extraBytes > MAX_RING_BYTES) {
                        return null;
                    }
                }
            }
        }
        return new int[]{cx0, cx1, cy0, cy1};
    }

    /**
     * Decode the coarsest pyramid level into the tile cache in one merged
     * fetch, when its total raw size stays under {@code maxBytes} (phase
     * 2.7 E2): the initial zoomed-out view and zoom-out fallbacks then cost
     * zero requests. Returns the number of tiles prefetched, 0 when the
     * prefetch was skipped (single-level COG, oversized overview).
     */
    public int prefetchBaseOverview(long maxBytes) throws IOException {
        int li = info.levelCount() - 1;
        if (li < 1) {
            return 0;
        }
        CogInfo.Level lvl = info.level(li);
        long total = 0;
        for (long bc : lvl.tileByteCounts) {
            total += Math.min(Math.max(0, bc), maxBytes + 1);
            if (total > maxBytes) {
                return 0;
            }
        }
        return getTiles(li, 0, lvl.numXTiles() - 1, 0, lvl.numYTiles() - 1).size();
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
