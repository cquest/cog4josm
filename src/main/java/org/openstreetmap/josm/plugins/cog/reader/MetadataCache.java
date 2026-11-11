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
 * <p>Entries are keyed by source URL plus exact (offset, length). The cache
 * is validated on each new open with a (size, ETag) fingerprint: a changed
 * fingerprint drops everything cached for that URL.</p>
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

    private final long maxTotalBytes;
    private final int maxEntryBytes;
    private final Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>(64, 0.75f, true);
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

    /** Cached bytes for this exact range, or null on a miss (defensive copy). */
    public synchronized byte[] lookup(String url, long offset, int length) {
        byte[] v = entries.get(key(url, offset, length));
        return v == null ? null : v.clone();
    }

    /** Stores a freshly read metadata block (oversized blocks are skipped). */
    public synchronized void store(String url, long offset, int length, byte[] data) {
        if (data == null || data.length == 0 || data.length > maxEntryBytes) {
            return;
        }
        entries.put(key(url, offset, length), data.clone());
        totalBytes += data.length;
        while (totalBytes > maxTotalBytes && !entries.isEmpty()) {
            Map.Entry<String, byte[]> eldest = entries.entrySet().iterator().next();
            totalBytes -= eldest.getValue().length;
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
        String prefix = url + '\u0000';
        entries.entrySet().removeIf(e -> {
            if (e.getKey().startsWith(prefix)) {
                totalBytes -= e.getValue().length;
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
