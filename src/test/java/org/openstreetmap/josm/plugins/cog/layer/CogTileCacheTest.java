package org.openstreetmap.josm.plugins.cog.layer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.jcs3.access.behavior.ICacheAccess;
import org.junit.jupiter.api.Test;
import org.openstreetmap.gui.jmapviewer.Tile;
import org.openstreetmap.gui.jmapviewer.TileXY;
import org.openstreetmap.gui.jmapviewer.interfaces.TileLoaderListener;
import org.openstreetmap.josm.data.cache.BufferedImageCacheEntry;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.plugins.cog.reader.ByteSource;
import org.openstreetmap.josm.plugins.cog.reader.CogReader;
import org.openstreetmap.josm.plugins.cog.reader.MercatorTiles;
import org.openstreetmap.josm.plugins.cog.reader.TestTiffWriter;

/**
 * The JOSM tile cache region of the layer ("cog") stores the COMPOSED display
 * tiles (never COG bytes): fingerprinted keys, PNG round-trips, error and
 * blank tiles excluded. The JCS region is replaced here by a Map-backed
 * proxy of ICacheAccess (the plugin only ever calls get/put/remove).
 */
class CogTileCacheTest {

    /** 64x64 px EPSG:3857 COG of 10 m pixels (640 m wide) centred near Paris. */
    private static final double ORIGIN_X = 250_000.0;
    private static final double ORIGIN_Y = 6_250_000.0;

    private static final class CountingSource implements ByteSource {

        private final byte[] data;
        int reads;

        CountingSource(byte[] data) {
            this.data = data;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public boolean supportsRanges() {
            return true;
        }

        @Override
        public synchronized byte[] read(long offset, int length) {
            reads++;
            if (offset >= data.length) {
                return new byte[0];
            }
            return Arrays.copyOfRange(data, (int) offset, (int) Math.min(offset + length, data.length));
        }

        @Override
        public void close() {
        }
    }

    private record Fixture(CogTileSource source, CountingSource backend) {
    }

    /** Map-backed stand-in of the JCS region (get/put/remove only). */
    private static ICacheAccess<String, BufferedImageCacheEntry> fakeCache(
            Map<String, BufferedImageCacheEntry> backing) {
        return (ICacheAccess<String, BufferedImageCacheEntry>) Proxy.newProxyInstance(
                CogTileCacheTest.class.getClassLoader(),
                new Class<?>[] { ICacheAccess.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "get":
                            return backing.get((String) args[0]);
                        case "put":
                            backing.put((String) args[0], (BufferedImageCacheEntry) args[1]);
                            return Boolean.TRUE; // JCS3 ICache.put returns boolean
                        case "remove":
                            return backing.remove((String) args[0]) != null;
                        case "toString":
                            return "fakeJcsCache";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private static final TileLoaderListener NOOP_LISTENER =
            (tile, success) -> { /* results are asserted on the Tile itself */ };

    private static Fixture fixture3857() throws IOException {
        byte[] tiff = TestTiffWriter.write(
                List.of(new TestTiffWriter.LevelSpec(64, 64, 8, 1, 8, false)),
                3857, 10.0, ORIGIN_X, ORIGIN_Y);
        CountingSource src = new CountingSource(tiff);
        CogReader reader = CogReader.open(src);
        ImageryInfo info = new ImageryInfo("cog cache test", "http://localhost/cache-test.tif");
        return new Fixture(new CogTileSource(info, reader), src);
    }

    /** Tile position (zoom 12) whose area covers the COG centre. */
    private static int[] tileCoveringCogCentre(CogTileSource source) {
        double cx = ORIGIN_X + 320.0;
        double cy = ORIGIN_Y - 320.0;
        TileXY txy = source.latLonToTileXY(MercatorTiles.yToLat(cy), MercatorTiles.xToLon(cx), 12);
        return new int[] { 12, txy.getXIndex(), txy.getYIndex() };
    }

    @Test
    void fingerprintIsStableAndSensitiveToEveryField() {
        String a = CogTileSource.fingerprint("http://x/a.tif", 1000L, "\"E1\"");
        assertEquals(a, CogTileSource.fingerprint("http://x/a.tif", 1000L, "\"E1\""));
        assertTrue(a.matches("[0-9a-f]{16}"), "compact hex key: " + a);
        assertNotEquals(a, CogTileSource.fingerprint("http://x/a.tif", 1001L, "\"E1\""));
        assertNotEquals(a, CogTileSource.fingerprint("http://x/a.tif", 1000L, "\"E2\""));
        assertNotEquals(a, CogTileSource.fingerprint("http://x/b.tif", 1000L, "\"E1\""));
        // a source without ETag (non-HTTP) and an unknown size still fingerprint fine
        assertNotNull(CogTileSource.fingerprint("http://x/a.tif", -1L, ""));
    }

    @Test
    void cacheKeyCarriesFingerprintAndPosition() throws IOException {
        CogTileSource source = fixture3857().source();
        String fingerprint = source.getCacheFingerprint();
        assertEquals(16, fingerprint.length());
        assertEquals(fingerprint + "/12/3/5", source.cacheKey(12, 3, 5));
        assertNotEquals(source.cacheKey(12, 3, 5), source.cacheKey(12, 3, 6));
    }

    @Test
    void composedTileIsCachedAndServedWithoutByteRanges() throws IOException {
        Fixture fx = fixture3857();
        Map<String, BufferedImageCacheEntry> backing = new ConcurrentHashMap<>();
        CogTileLoader loader = new CogTileLoader(NOOP_LISTENER, fakeCache(backing));
        int[] pos = tileCoveringCogCentre(fx.source());

        // NOTE: the JMapViewer Tile constructor takes (source, x, y, zoom)
        Tile first = new Tile(fx.source(), pos[1], pos[2], pos[0]);
        new CogTileLoaderJob(loader, first).run();
        assertTrue(first.isLoaded());
        assertFalse(first.hasError());
        assertNotNull(first.getImage());
        assertFalse(CogTileLoaderJob.isBlank(first.getImage()), "tile covers the COG: not blank");
        assertEquals(1, backing.size(), "one composed tile must be cached");
        assertNotNull(backing.get(fx.source().cacheKey(pos[0], pos[1], pos[2])),
                "cache key = fingerprint/zoom/x/y");

        // a second tile at the same position must be served from the cache
        // without any new byte-range read (PNG round-trip included)
        int readsBefore = fx.backend().reads;
        Tile second = new Tile(fx.source(), pos[1], pos[2], pos[0]);
        new CogTileLoaderJob(loader, second).run();
        assertTrue(second.isLoaded());
        assertFalse(second.hasError());
        assertEquals(readsBefore, fx.backend().reads, "cache hit must not read the COG again");

        // pixel-identical through the PNG round-trip
        BufferedImage img1 = first.getImage();
        BufferedImage img2 = second.getImage();
        assertArrayEquals(img1.getRGB(0, 0, img1.getWidth(), img1.getHeight(), null, 0, img1.getWidth()),
                img2.getRGB(0, 0, img2.getWidth(), img2.getHeight(), null, 0, img2.getWidth()),
                "PNG round-trip keeps every pixel");
    }

    @Test
    void blankTileIsNotCached() throws IOException {
        Fixture fx = fixture3857();
        Map<String, BufferedImageCacheEntry> backing = new ConcurrentHashMap<>();
        CogTileLoader loader = new CogTileLoader(NOOP_LISTENER, fakeCache(backing));
        int[] pos = tileCoveringCogCentre(fx.source());
        // far away tile at the same zoom: composition misses the COG (null image)
        Tile blank = new Tile(fx.source(), pos[1] + 30, pos[2] + 30, pos[0]);
        new CogTileLoaderJob(loader, blank).run();
        assertTrue(blank.isLoaded());
        assertFalse(blank.hasError());
        assertTrue(backing.isEmpty(), "tiles that miss the COG are not worth caching");
    }

    @Test
    void corruptCacheEntryIsDroppedAndReplaced() throws IOException {
        Fixture fx = fixture3857();
        Map<String, BufferedImageCacheEntry> backing = new ConcurrentHashMap<>();
        CogTileLoader loader = new CogTileLoader(NOOP_LISTENER, fakeCache(backing));
        int[] pos = tileCoveringCogCentre(fx.source());
        String key = fx.source().cacheKey(pos[0], pos[1], pos[2]);
        backing.put(key, new BufferedImageCacheEntry(new byte[] { 1, 2, 3, 4 })); // undecodable

        Tile tile = new Tile(fx.source(), pos[1], pos[2], pos[0]);
        new CogTileLoaderJob(loader, tile).run();
        assertTrue(tile.isLoaded());
        assertFalse(tile.hasError(), "a corrupt entry is a miss, not a failure");
        assertEquals(1, backing.size());
        assertNotNull(backing.get(key).getImage(), "replaced by a valid entry");
    }

    @Test
    void blankDetectionOnSyntheticImages() {
        BufferedImage blank = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        assertTrue(CogTileLoaderJob.isBlank(blank));
        blank.setRGB(3, 4, 0x01FF_0000);
        assertFalse(CogTileLoaderJob.isBlank(blank));
        BufferedImage opaque = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        assertFalse(CogTileLoaderJob.isBlank(opaque), "no alpha channel: fully opaque");
    }

    @Test
    void noCacheMeansNoCrash() throws IOException {
        Fixture fx = fixture3857();
        CogTileLoader loader = new CogTileLoader(NOOP_LISTENER, null);
        int[] pos = tileCoveringCogCentre(fx.source());
        Tile tile = new Tile(fx.source(), pos[1], pos[2], pos[0]);
        new CogTileLoaderJob(loader, tile).run();
        assertTrue(tile.isLoaded());
        assertFalse(tile.hasError());
    }
}
