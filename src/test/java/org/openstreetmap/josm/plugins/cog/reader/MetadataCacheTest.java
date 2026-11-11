package org.openstreetmap.josm.plugins.cog.reader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** LRU budget, containment slicing, defensive copies and fingerprint invalidation. */
class MetadataCacheTest {

    @TempDir
    Path storeDir;

    @Test
    void exactHitsAndCoveringBlocksAreSliced() {
        MetadataCache c = new MetadataCache(1 << 20, 1 << 16);
        c.validate("u", 999, null);
        c.store("u", 10, new byte[]{1, 2, 3, 4, 5});
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, c.lookup("u", 10, 5));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, c.lookup("u", 10, 4), "smaller range is sliced");
        assertArrayEquals(new byte[]{2, 3}, c.lookup("u", 11, 2), "inner range is sliced");
        assertNull(c.lookup("u", 10, 6), "no block covers a longer range");
        assertNull(c.lookup("u", 9, 5), "no block covers a range starting earlier");
        assertNull(c.lookup("other", 10, 5), "blocks are bound to their URL");
    }

    @Test
    void headBlockServesMetadataLookupsWithoutRequests() {
        MetadataCache c = new MetadataCache(1 << 20, 1 << 16);
        byte[] head = new byte[4096];
        for (int i = 0; i < head.length; i++) {
            head[i] = (byte) i;
        }
        c.store("u", 0, head); // the speculative head read of CogReader.open
        byte[] ifd = c.lookup("u", 8, 4);
        assertNotNull(ifd);
        assertArrayEquals(new byte[]{8, 9, 10, 11}, ifd);
    }

    @Test
    void lookupReturnsDefensiveCopy() {
        MetadataCache c = new MetadataCache(1 << 20, 1 << 16);
        c.store("u", 0, new byte[]{7, 7, 7});
        byte[] a = c.lookup("u", 0, 3);
        a[0] = 0;
        assertArrayEquals(new byte[]{7, 7, 7}, c.lookup("u", 0, 3));
        byte[] slice = c.lookup("u", 1, 2); // written through the slice?
        slice[0] = 0;
        assertArrayEquals(new byte[]{7, 7, 7}, c.lookup("u", 0, 3), "slices are copies too");
    }

    @Test
    void oversizedEntriesAreNotCached() {
        MetadataCache c = new MetadataCache(1 << 20, 512);
        c.store("u", 0, new byte[1000]);
        assertNull(c.lookup("u", 0, 1000));
        assertNull(c.lookup("u", 0, 8), "oversized block is not sliced either");
    }

    @Test
    void evictsLeastRecentlyUsedBeyondBudget() {
        MetadataCache c = new MetadataCache(1000, 400);
        c.store("u", 0, new byte[300]);      // A
        c.store("u", 1000, new byte[300]);   // B
        assertNotNull(c.lookup("u", 1000, 300));  // B becomes the most recent
        c.store("u", 2000, new byte[300]);   // C: 900 total, still fits
        c.store("u", 3000, new byte[300]);   // D: 1200 -> evicts eldest (A)
        assertNull(c.lookup("u", 0, 300));
        assertNotNull(c.lookup("u", 1000, 300));
        assertNotNull(c.lookup("u", 2000, 300));
        assertNotNull(c.lookup("u", 3000, 300));
        assertTrue(c.cachedBytes() <= 1000);
    }

    @Test
    void restoringSameKeyDoesNotDoubleCount() {
        MetadataCache c = new MetadataCache(1 << 20, 1 << 16);
        c.store("u", 0, new byte[100]);
        c.store("u", 0, new byte[100]); // same block fetched again
        assertTrue(c.cachedBytes() == 100, "budget: " + c.cachedBytes());
    }

    @Test
    void changedFingerprintDropsUrlEntries() {
        MetadataCache c = new MetadataCache(1 << 20, 1 << 16);
        c.validate("u", 100, "\"v1\"");
        c.validate("keep", 100, null);
        c.store("u", 0, new byte[]{1, 2, 3, 4});
        c.store("keep", 0, new byte[]{5, 6, 7, 8});
        c.validate("u", 100, "\"v2\""); // ETag changed
        assertNull(c.lookup("u", 0, 4));
        assertNotNull(c.lookup("keep", 0, 4), "other URLs untouched");
        c.store("u", 0, new byte[]{1, 2, 3, 4});
        c.validate("u", 101, "\"v2\""); // size changed
        assertNull(c.lookup("u", 0, 4));
    }

    @Test
    void knownIdentityComesFromAttachedStore() {
        FileMetadataStore s = new FileMetadataStore(storeDir, "t");
        s.writeAll("u", 500, "\"v1\"",
                List.of(new FileMetadataStore.Persisted(0, new byte[]{1, 2, 3})));
        MetadataCache c = new MetadataCache(1 << 20, 1 << 16);
        assertNull(c.knownIdentity("u"), "no store attached: nothing known");
        c.attachStore(s);
        MetadataCache.Identity id = c.knownIdentity("u");
        assertNotNull(id);
        assertEquals(500, id.size());
        assertEquals("\"v1\"", id.etag());
        c.attachStore(null);
        MetadataCache c2 = new MetadataCache(1 << 20, 1 << 16);
        assertNull(c2.knownIdentity("u"), "without a store, nothing is known");
    }

    @Test
    void lookupLoadsPersistedBlocksOnce() {
        FileMetadataStore s = new FileMetadataStore(storeDir, "t");
        String url = "http://example.org/l.tif";
        s.writeAll(url, 100, "\"v1\"", List.of(new FileMetadataStore.Persisted(0, new byte[]{1, 2, 3, 4})));
        MetadataCache c = new MetadataCache(1 << 20, 1 << 16);
        c.attachStore(s);
        assertArrayEquals(new byte[]{2, 3}, c.lookup(url, 1, 2), "slice of a persisted block");
        // rewrite the store with different bytes: the memory is not rescanned
        s.writeAll(url, 100, "\"v1\"", List.of(new FileMetadataStore.Persisted(0, new byte[]{9, 9, 9, 9})));
        assertArrayEquals(new byte[]{2, 3}, c.lookup(url, 1, 2), "one scan per URL, then memory only");
    }

    @Test
    void mirrorAndStoreRoundTripAcrossCaches() {
        FileMetadataStore s = new FileMetadataStore(storeDir, "t");
        String url = "http://example.org/m.tif";
        MetadataCache c = new MetadataCache(1 << 20, 1 << 16);
        c.validate(url, 4, "\"abc\"");
        c.store(url, 0, new byte[]{1, 2, 3, 4});
        c.attachStore(s);
        c.mirrorToStore(url);
        assertEquals(4, s.readIdentity(url).size());
        assertEquals("\"abc\"", s.readIdentity(url).etag());
        // a brand new in-memory cache (simulating a restart) reads from disk:
        MetadataCache restarted = new MetadataCache(1 << 20, 1 << 16);
        restarted.attachStore(s);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, restarted.lookup(url, 0, 4));
        MetadataCache.Identity known = restarted.knownIdentity(url);
        assertNotNull(known);
        assertEquals("\"abc\"", known.etag(), "revalidation can be conditional");
    }

    @Test
    void fingerprintChangeDropsTheStoreEntryToo() {
        FileMetadataStore s = new FileMetadataStore(storeDir, "t");
        String url = "http://example.org/d.tif";
        MetadataCache c = new MetadataCache(1 << 20, 1 << 16);
        c.validate(url, 100, "\"v1\"");
        c.store(url, 0, new byte[]{1, 2, 3, 4});
        c.attachStore(s);
        c.mirrorToStore(url);
        assertNotNull(s.readIdentity(url));
        c.validate(url, 100, "\"v2\""); // the source changed
        assertNull(s.readIdentity(url), "stale persisted blocks dropped as well");
    }
}
