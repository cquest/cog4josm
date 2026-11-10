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
 * Chargeur de tuiles "COG" : ne telecharge aucune URL, fabrique les tuiles a la
 * volee a partir du lecteur COG attache a la source de tuiles (byte-ranges +
 * recomposition).
 */
public final class CogTileLoader implements TileLoader {

    private static final int POOL_SIZE = 4;

    private final TileLoaderListener listener;
    private volatile ExecutorService pool;

    /**
     * Constructeur standard exige par la reflexion de CachedTileLoaderFactory
     * (JOSM le recherche a la construction de la couche). Le cache JCS et les
     * options de telechargement HTTP ne sont pas utilises ici : les tuiles
     * sont synthetisees, jamais telechargees par URL.
     */
    public CogTileLoader(TileLoaderListener listener, ICacheAccess<String, BufferedImageCacheEntry> cache,
            TileJobOptions options) {
        this(listener);
    }

    CogTileLoader(TileLoaderListener listener) {
        this.listener = listener;
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

    /** Arret definitif (couche detruite). */
    public void dispose() {
        pool.shutdownNow();
    }
}
