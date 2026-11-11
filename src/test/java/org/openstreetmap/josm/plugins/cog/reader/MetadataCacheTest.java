package org.openstreetmap.josm.plugins.cog.reader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** LRU budget, defensive copies and fingerprint invalidation. */
class MetadataCacheTest {

    @Test
    void storesAndLooksUpExactRanges() {
        MetadataCache c = new MetadataCache(1 << 20, 1 << 16);
        c.validate("u", 999, null);
        c.store("u", 10, 5, new byte[]{1, 2, 3, 4, 5});
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, c.lookup("u", 10, 5));
        assertNull(c.lookup("u", 10, 4), "key is the exact range");
        assertNull(c.lookup("other", 10, 5));
    }

    @Test
    void lookupReturnsDefensiveCopy() {
        MetadataCache c = new MetadataCache(1 << 20, 1 << 16);
        c.store("u", 0, 3, new byte[]{7, 7, 7});
        byte[] a = c.lookup("u", 0, 3);
        a[0] = 0;
        assertArrayEquals(new byte[]{7, 7, 7}, c.lookup("u", 0, 3));
    }

    @Test
    void oversizedEntriesAreNotCached() {
        MetadataCache c = new MetadataCache(1 << 20, 512);
        c.store("u", 0, 1000, new byte[1000]);
        assertNull(c.lookup("u", 0, 1000));
    }

    @Test
    void evictsLeastRecentlyUsedBeyondBudget() {
        MetadataCache c = new MetadataCache(1000, 400);
        c.store("u", 0, 300, new byte[300]);      // A
        c.store("u", 1000, 300, new byte[300]);   // B
        assertNotNull(c.lookup("u", 1000, 300));  // B becomes the most recent
        c.store("u", 2000, 300, new byte[300]);   // C: 900 total, still fits
        c.store("u", 3000, 300, new byte[300]);   // D: 1200 -> evicts eldest (A)
        assertNull(c.lookup("u", 0, 300));
        assertNotNull(c.lookup("u", 1000, 300));
        assertNotNull(c.lookup("u", 2000, 300));
        assertNotNull(c.lookup("u", 3000, 300));
        assertTrue(c.cachedBytes() <= 1000);
    }

    @Test
    void changedFingerprintDropsUrlEntries() {
        MetadataCache c = new MetadataCache(1 << 20, 1 << 16);
        c.validate("u", 100, "\"v1\"");
        c.validate("keep", 100, null);
        c.store("u", 0, 4, new byte[]{1, 2, 3, 4});
        c.store("keep", 0, 4, new byte[]{5, 6, 7, 8});
        c.validate("u", 100, "\"v2\""); // ETag changed
        assertNull(c.lookup("u", 0, 4));
        assertNotNull(c.lookup("keep", 0, 4), "other URLs untouched");
        c.store("u", 0, 4, new byte[]{1, 2, 3, 4});
        c.validate("u", 101, "\"v2\""); // size changed
        assertNull(c.lookup("u", 0, 4));
    }
}
