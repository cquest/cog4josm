package org.openstreetmap.josm.plugins.cog.layer;

import java.util.concurrent.RejectedExecutionException;

import org.apache.commons.jcs3.access.behavior.ICacheAccess;
import org.openstreetmap.gui.jmapviewer.Tile;
import org.openstreetmap.gui.jmapviewer.interfaces.TileJob;
import org.openstreetmap.josm.data.cache.BufferedImageCacheEntry;
import org.openstreetmap.josm.plugins.cog.reader.Bounds;
import org.openstreetmap.josm.plugins.cog.reader.CogComposer;
import org.openstreetmap.josm.plugins.cog.reader.CogInfo;
import org.openstreetmap.josm.plugins.cog.reader.CogReader;
import org.openstreetmap.josm.plugins.cog.reader.MercatorTiles;
import org.openstreetmap.josm.tools.Logging;

import java.awt.image.BufferedImage;

/**
 * Tile building job: converts a tile of the OSM grid into a box in the COG's
 * coordinate reference system, then lets {@link CogComposer} assemble the
 * source tiles required (fetched by byte-ranges). The composed 256 px image
 * is a display tile: like any JOSM imagery layer, it is kept in the layer's
 * JCS cache region (memory + JOSM disk cache); the raw COG bytes are never
 * stored there.
 */
final class CogTileLoaderJob implements TileJob {

    private final CogTileLoader loader;
    private final Tile tile;

    CogTileLoaderJob(CogTileLoader loader, Tile tile) {
        this.loader = loader;
        this.tile = tile;
    }

    @Override
    public void submit() {
        submit(false);
    }

    @Override
    public void submit(boolean force) {
        if (tile.isLoaded() || tile.isLoading()) {
            return;
        }
        tile.initLoading();
        try {
            loader.executor().execute(this);
        } catch (RejectedExecutionException e) {
            tile.finishLoading();
            tile.setError("cancelled");
            loader.listener().tileLoadingFinished(tile, false);
        }
    }

    @Override
    public void run() {
        boolean success = false;
        try {
            if (!(tile.getSource() instanceof CogTileSource cts)) {
                Logging.warn("josm-cog: foreign tile source {0}",
                        tile.getSource() == null ? "null" : tile.getSource().getClass().getName());
                return;
            }
            CogReader reader = cts.getReader();
            CogInfo info = reader.info();
            int size = tile.getSource().getTileSize();
            ICacheAccess<String, BufferedImageCacheEntry> cache = loader.cache();
            String cacheKey = cache == null
                    ? null : cts.cacheKey(tile.getZoom(), tile.getXtile(), tile.getYtile());
            if (cacheKey != null) {
                BufferedImage cached = lookupCachedTile(cache, cacheKey);
                if (cached != null) {
                    tile.finishLoading();
                    tile.setImage(cached);
                    tile.setLoaded(true);
                    loader.listener().tileLoadingFinished(tile, true);
                    return;
                }
            }
            Bounds bbox = MercatorTiles.tileBounds(tile.getZoom(), tile.getXtile(), tile.getYtile());
            BufferedImage img;
            if (cts.isGeographicDegrees()) {
                bbox = new Bounds(MercatorTiles.xToLon(bbox.minX()), MercatorTiles.yToLat(bbox.minY()),
                        MercatorTiles.xToLon(bbox.maxX()), MercatorTiles.yToLat(bbox.maxY()));
                img = CogComposer.compose(reader, bbox, size);
            } else if (info.crsCode == 3857) {
                img = CogComposer.compose(reader, bbox, size);
            } else {
                org.openstreetmap.josm.plugins.cog.crs.Projection proj = cts.getProjection();
                if (proj == null) {
                    tile.finishLoading();
                    tile.setError(cts.getCrsError() == null
                            ? info.crsDescription() + " not supported" : cts.getCrsError());
                    loader.listener().tileLoadingFinished(tile, false);
                    return;
                }
                img = CogComposer.composeWarp(reader, proj, bbox, size);
            }
            tile.finishLoading();
            if (img != null) {
                tile.setImage(img);
                // error and CRS paths never reach this point: only composed,
                // non-empty display tiles go into the JOSM cache; a cache
                // failure never fails the tile itself. Tiles that miss the
                // COG entirely compose to null and are not cached either.
                if (cacheKey != null && !isBlank(img)) {
                    storeCachedTile(cache, cacheKey, img);
                }
            }
            tile.setLoaded(true);
            success = true;
        } catch (Exception e) {
            Logging.warn("josm-cog: error on tile {0}: {1}", tile.getKey(), e);
            tile.finishLoading();
            tile.setError(e);
        }
        loader.listener().tileLoadingFinished(tile, success);
    }

    /**
     * Reads a composed tile from the JOSM cache; a corrupt or undecodable
     * entry is dropped and treated as a miss.
     */
    static BufferedImage lookupCachedTile(ICacheAccess<String, BufferedImageCacheEntry> cache, String key) {
        try {
            BufferedImageCacheEntry entry = cache.get(key);
            return entry == null ? null : entry.getImage();
        } catch (Exception e) {
            Logging.warn("josm-cog: unreadable cached tile {0}: {1}", key, e);
            try {
                cache.remove(key);
            } catch (Exception ignored) {
                // best effort: the miss path recomputes the tile anyway
            }
            return null;
        }
    }

    /** Stores a composed tile, PNG-encoded so the JOSM disk cache can keep it. */
    static void storeCachedTile(ICacheAccess<String, BufferedImageCacheEntry> cache, String key, BufferedImage img) {
        try {
            cache.put(key, BufferedImageCacheEntry.pngEncoded(img));
        } catch (Exception e) {
            Logging.warn("josm-cog: cannot cache tile {0}: {1}", key, e);
        }
    }

    /** True when every pixel is fully transparent (tile outside the COG: not worth caching). */
    static boolean isBlank(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFF00_0000) != 0) {
                    return false;
                }
            }
        }
        return true;
    }
}
