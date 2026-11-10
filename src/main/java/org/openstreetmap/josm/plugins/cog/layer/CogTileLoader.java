package org.openstreetmap.josm.plugins.cog.layer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.openstreetmap.gui.jmapviewer.Tile;
import org.openstreetmap.gui.jmapviewer.interfaces.TileJob;
import org.openstreetmap.gui.jmapviewer.interfaces.TileLoader;
import org.openstreetmap.gui.jmapviewer.interfaces.TileLoaderListener;
import org.openstreetmap.josm.plugins.cog.reader.CogReader;

/**
 * Chargeur de tuiles "COG" : ne telecharge aucune URL, fabrique les tuiles a la
 * volee a partir du {@link CogReader} (byte-ranges + recomposition).
 */
public final class CogTileLoader implements TileLoader {

    private static final int POOL_SIZE = 4;

    private final TileLoaderListener listener;
    private final CogReader reader;
    private volatile ExecutorService pool;

    public CogTileLoader(TileLoaderListener listener, CogReader reader) {
        this.listener = listener;
        this.reader = reader;
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

    CogReader reader() {
        return reader;
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
