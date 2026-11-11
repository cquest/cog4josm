package org.openstreetmap.josm.plugins.cog.reader;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * LRU byte cache dedicated to TIFF <em>metadata</em>: header, IFD blocks,
 * external tag values (including tile offsets/byte counts arrays). Raster
 * tile content must never go through this cache — it is fetched on demand
 * and only kept as decoded images by {@link CogReader}.
 *
 * <p>Entries are blocks keyed by source URL plus their exact (offset, byte
 * count). A lookup first tries the exact range, then slices any cached block
 * of the same URL that fully covers the requested range: the speculative
 * head read done by {@link CogReader#open(String, Map)} can thus serve every
 * metadata read of a well-laid-out COG without further requests.</p>
 *
 * <p>The cache is validated on each new open with a (size, ETag) fingerprint:
 * a changed fingerprint drops everything cached for that URL.</p>
 */
public final class MetadataCache {

    private static final MetadataCache GLOBAL = new MetadataCache(8L << 20, 4 << 20);

    public static MetadataCache global() {
        return GLOBAL;
    }

    private static final class Fingerprint {
        final long size;
        final String etag;

        Fingerprint(long size, String etag) {
            this.size = size;
            this.etag = etag;
        }
    }

    /** Cached bytes of [offset, offset + data.length) within one URL. */
    private static final class Block {
        final String url;
        final long offset;
        final byte[] data;

        Block(String url, long offset, byte[] data) {
            this.url = url;
            this.offset = offset;
            this.data = data;
        }
    }

    private final long maxTotalBytes;
    private final int maxEntryBytes;
    private final Map<String, Block> entries = new LinkedHashMap<String, Block>(64, 0.75f, true);
    private final Map<String, Fingerprint> fingerprints = new HashMap<>();
    private long totalBytes;

    /**
     * @param maxTotalBytes total budget of cached bytes (LRU eviction beyond it)
     * @param maxEntryBytes entries larger than this are not cached at all
     */
    public MetadataCache(long maxTotalBytes, int maxEntryBytes) {
        this.maxTotalBytes = maxTotalBytes;
        this.maxEntryBytes = maxEntryBytes;
    }

    /**
     * Cached bytes for (offset, length): exact hit, else a slice of any
     * cached block of this URL covering the whole range; null on a miss.
     * The result is always a defensive copy.
     */
    public synchronized byte[] lookup(String url, long offset, int length) {
        Block exact = entries.get(key(url, offset, length));
        if (exact != null) {
            return exact.data.clone();
        }
        for (Block b : entries.values()) {
            if (b.url.equals(url) && b.offset <= offset && b.data.length >= length
                    && b.offset + b.data.length >= offset + length) {
                byte[] slice = new byte[length];
                System.arraycopy(b.data, (int) (offset - b.offset), slice, 0, length);
                return slice;
            }
        }
        return null;
    }

    /** Stores a freshly read metadata block (oversized blocks are skipped). */
    public synchronized void store(String url, long offset, byte[] data) {
        if (data == null || data.length == 0 || data.length > maxEntryBytes) {
            return;
        }
        byte[] copy = data.clone();
        Block previous = entries.put(key(url, offset, copy.length), new Block(url, offset, copy));
        totalBytes += copy.length - (previous == null ? 0 : previous.data.length);
        while (totalBytes > maxTotalBytes && !entries.isEmpty()) {
            Map.Entry<String, Block> eldest = entries.entrySet().iterator().next();
            totalBytes -= eldest.getValue().data.length;
            entries.remove(eldest.getKey());
        }
    }

    /**
     * Checks the source fingerprint; when it changed (or is new), all entries
     * of the URL are dropped and the new fingerprint recorded.
     */
    public synchronized void validate(String url, long size, String etag) {
        Fingerprint f = fingerprints.get(url);
        if (f == null || f.size != size || !Objects.equals(f.etag, etag)) {
            clearUrl(url);
            fingerprints.put(url, new Fingerprint(size, etag));
        }
    }

    public synchronized void clearUrl(String url) {
        entries.entrySet().removeIf(e -> {
            if (e.getValue().url.equals(url)) {
                totalBytes -= e.getValue().data.length;
                return true;
            }
            return false;
        });
    }

    /** Full reset, for tests. */
    public synchronized void clearAll() {
        entries.clear();
        fingerprints.clear();
        totalBytes = 0;
    }

    public synchronized long cachedBytes() {
        return totalBytes;
    }

    private static String key(String url, long offset, int length) {
        return url + '\u0000' + offset + '\u0000' + length;
    }
}
