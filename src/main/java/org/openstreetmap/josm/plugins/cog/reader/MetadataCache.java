package org.openstreetmap.josm.plugins.cog.reader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 *
 * <p>Optionally (phase 2.8) a {@link FileMetadataStore} persists the blocks
 * across JOSM sessions: {@link #knownIdentity(String)} returns the stored
 * fingerprint so {@link CogReader#open(String, java.util.Map)} can send a
 * conditional request (a 304 answer costs zero body bytes), {@link
 * #lookup} transparently loads stored blocks on a memory miss, and {@link
 * #mirrorToStore} refreshes the store after a validated head read.</p>
 */
public final class MetadataCache {

    private static final MetadataCache GLOBAL = new MetadataCache(8L << 20, 4 << 20);

    public static MetadataCache global() {
        return GLOBAL;
    }

    /** The (size, ETag) fingerprint of one URL. */
    public record Identity(long size, String etag) {
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
    private volatile FileMetadataStore store;
    /** URLs whose persisted blocks were already scanned into memory. */
    private final Set<String> loadedUrls = new HashSet<>();
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
     * Attaches (or with null, detaches) the on-disk metadata store; the
     * global cache is what {@link CogReader} uses. Only the plugin should
     * call this, in practice {@code MetadataCache.global().attachStore(...)}.
     */
    public void attachStore(FileMetadataStore newStore) {
        synchronized (this) {
            store = newStore;
            loadedUrls.clear();
        }
    }

    /**
     * The known fingerprint of a URL — from a previous validation in this
     * session, else from the attached store (also remembered for the
     * session). Null when unknown: the caller then does a plain head read.
     */
    public synchronized Identity knownIdentity(String url) {
        Fingerprint f = fingerprints.get(url);
        if (f != null) {
            return new Identity(f.size, f.etag);
        }
        FileMetadataStore s = store;
        if (s == null) {
            return null;
        }
        FileMetadataStore.Identity stored = s.readIdentity(url);
        if (stored == null) {
            return null;
        }
        fingerprints.put(url, new Fingerprint(stored.size(), stored.etag()));
        return new Identity(stored.size(), stored.etag());
    }

    /**
     * Persists the in-memory blocks of a URL (called after a validated
     * head read) so a later session can skip the re-download.
     */
    public synchronized void mirrorToStore(String url) {
        FileMetadataStore s = store;
        Fingerprint f = fingerprints.get(url);
        if (s == null || f == null) {
            return;
        }
        List<FileMetadataStore.Persisted> blocks = new ArrayList<>();
        entries.entrySet().stream()
                .filter(e -> e.getValue().url.equals(url))
                .forEach(e -> blocks.add(new FileMetadataStore.Persisted(e.getValue().offset, e.getValue().data)));
        s.writeAll(url, f.size, f.etag, blocks);
    }

    /**
     * Cached bytes for (offset, length): exact hit, else a slice of any
     * cached block of this URL covering the whole range (filling the memory
     * from the store once per URL on a first miss); null on a miss.
     * The result is always a defensive copy.
     */
    public synchronized byte[] lookup(String url, long offset, int length) {
        Block exact = entries.get(key(url, offset, length));
        if (exact != null) {
            return exact.data.clone();
        }
        loadFromStore(url);
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
        insert(new Block(url, offset, data.clone()));
    }

    /**
     * Checks the source fingerprint; when it changed (or is new), all entries
     * of the URL are dropped (in memory and in the store) and the new
     * fingerprint recorded. A conditional 304 revalidates with the very same
     * fingerprint the store advertised, so nothing is dropped.
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
        loadedUrls.remove(url);
        FileMetadataStore s = store;
        if (s != null) {
            s.drop(url);
        }
    }

    /** Full reset, for tests. */
    public synchronized void clearAll() {
        entries.clear();
        fingerprints.clear();
        loadedUrls.clear();
        totalBytes = 0;
    }

    public synchronized long cachedBytes() {
        return totalBytes;
    }

    /**
     * On the first memory miss of a URL, scans its persisted blocks into
     * the LRU once (later misses do not rescan the disk).
     */
    private void loadFromStore(String url) {
        FileMetadataStore s = store;
        if (s == null || !loadedUrls.add(url)) {
            return;
        }
        for (FileMetadataStore.Persisted p : s.loadBlocks(url)) {
            insert(new Block(url, p.offset(), p.data()));
        }
    }

    private void insert(Block b) {
        Block previous = entries.put(key(b.url, b.offset, b.data.length), b);
        totalBytes += b.data.length - (previous == null ? 0 : previous.data.length);
        evictBeyondBudget();
    }

    private void evictBeyondBudget() {
        while (totalBytes > maxTotalBytes && !entries.isEmpty()) {
            Map.Entry<String, Block> eldest = entries.entrySet().iterator().next();
            totalBytes -= eldest.getValue().data.length;
            entries.remove(eldest.getKey());
        }
    }

    private static String key(String url, long offset, int length) {
        return url + '\u0000' + offset + '\u0000' + length;
    }
}
