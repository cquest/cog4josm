package org.openstreetmap.josm.plugins.cog.reader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** LRU budget, containment slicing, defensive copies and fingerprint invalidation. */
class MetadataCacheTest {

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
}
