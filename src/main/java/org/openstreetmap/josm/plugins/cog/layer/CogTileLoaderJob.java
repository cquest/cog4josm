package org.openstreetmap.josm.plugins.cog.layer;

import java.util.concurrent.RejectedExecutionException;

import org.openstreetmap.gui.jmapviewer.Tile;
import org.openstreetmap.gui.jmapviewer.interfaces.TileJob;
import org.openstreetmap.josm.plugins.cog.reader.Bounds;
import org.openstreetmap.josm.plugins.cog.reader.CogComposer;
import org.openstreetmap.josm.plugins.cog.reader.CogInfo;
import org.openstreetmap.josm.plugins.cog.reader.CogReader;
import org.openstreetmap.josm.plugins.cog.reader.MercatorTiles;
import org.openstreetmap.josm.tools.Logging;

import java.awt.image.BufferedImage;

/**
 * Job de fabrication d'une tuile : convertit la tuile de la grille OSM en boite
 * dans le systeme de coordonnees du COG, puis laisse le {@link CogComposer} assembler
 * les tuiles sources necessaires (chargees par byte-ranges).
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
                Logging.warn("josm-cog : source de tuile etrangere {0}",
                        tile.getSource() == null ? "null" : tile.getSource().getClass().getName());
                return;
            }
            CogReader reader = cts.getReader();
            CogInfo info = reader.info();
            int size = tile.getSource().getTileSize();
            Bounds bbox = MercatorTiles.tileBounds(tile.getZoom(), tile.getXtile(), tile.getYtile());
            if (info.crsCode == 4326) {
                bbox = new Bounds(MercatorTiles.xToLon(bbox.minX()), MercatorTiles.yToLat(bbox.minY()),
                        MercatorTiles.xToLon(bbox.maxX()), MercatorTiles.yToLat(bbox.maxY()));
            }
            BufferedImage img = CogComposer.compose(reader, bbox, size);
            tile.finishLoading();
            if (img != null) {
                tile.setImage(img);
            }
            tile.setLoaded(true);
            success = true;
        } catch (Exception e) {
            Logging.warn("josm-cog : erreur tuile {0} : {1}", tile.getKey(), e);
            tile.finishLoading();
            tile.setError(e);
        }
        loader.listener().tileLoadingFinished(tile, success);
    }
}
