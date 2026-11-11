package org.openstreetmap.josm.plugins.cog.layer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.jcs3.access.behavior.ICacheAccess;
import org.openstreetmap.gui.jmapviewer.Tile;
import org.openstreetmap.gui.jmapviewer.interfaces.TileJob;
import org.openstreetmap.gui.jmapviewer.interfaces.TileLoader;
import org.openstreetmap.gui.jmapviewer.interfaces.TileLoaderListener;
import org.openstreetmap.josm.data.cache.BufferedImageCacheEntry;
import org.openstreetmap.josm.data.imagery.TileJobOptions;

/**
 * "COG" tile loader: it downloads no URL and builds the tiles on the fly from
 * the COG reader attached to the tile source (byte-ranges + reassembly). The
 * composed display tiles are kept in the JOSM imagery tile cache region of
 * the layer (see {@link CogTileLoaderJob}); the raw COG bytes are not.
 */
public final class CogTileLoader implements TileLoader {

    private static final int POOL_SIZE = 4;

    private final TileLoaderListener listener;
    /** JOSM JCS region of the layer ("cog"); null when the host gave none. */
    private final ICacheAccess<String, BufferedImageCacheEntry> cache;
    private volatile ExecutorService pool;

    /**
     * Standard constructor required by the reflection used in CachedTileLoaderFactory
     * (JOSM looks it up when the layer is created). The JCS cache holds the
     * composed display tiles; the HTTP download options are unused: tiles are
     * synthesized, never downloaded by URL.
     */
    public CogTileLoader(TileLoaderListener listener, ICacheAccess<String, BufferedImageCacheEntry> cache,
            TileJobOptions options) {
        this(listener, cache);
    }

    CogTileLoader(TileLoaderListener listener, ICacheAccess<String, BufferedImageCacheEntry> cache) {
        this.listener = listener;
        this.cache = cache;
        this.pool = newPool();
    }

    private static ExecutorService newPool() {
        return Executors.newFixedThreadPool(POOL_SIZE, r -> {
            Thread t = new Thread(r, "cog-tile-loader");
            t.setDaemon(true);
            return t;
        });
    }

    ExecutorService executor() {
        return pool;
    }

    TileLoaderListener listener() {
        return listener;
    }

    /** JOSM tile cache region for composed tiles (null when unavailable). */
    ICacheAccess<String, BufferedImageCacheEntry> cache() {
        return cache;
    }

    @Override
    public TileJob createTileLoaderJob(Tile tile) {
        return new CogTileLoaderJob(this, tile);
    }

    @Override
    public void cancelOutstandingTasks() {
        ExecutorService current = pool;
        pool = newPool();
        current.shutdownNow();
    }

    /** Permanent shutdown (layer destroyed). */
    public void dispose() {
        pool.shutdownNow();
    }
}
