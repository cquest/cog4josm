package org.openstreetmap.josm.plugins.cog.layer;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

import org.openstreetmap.gui.jmapviewer.interfaces.TileLoader;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.data.imagery.TileLoaderFactory;
import org.openstreetmap.josm.gui.layer.AbstractCachedTileSourceLayer;
import org.openstreetmap.josm.plugins.cog.reader.CogReader;
import org.openstreetmap.josm.tools.Logging;

/**
 * Couche d'arriere-plan affichant un COG distant. Reutilise toute la machinerie
 * des couches d'imagerie JOSM (cache, zoom, transparence, filtres) ; seule la
 * fabrication des tuiles est particuliere ({@link CogTileLoader}).
 */
public class CogImageryLayer extends AbstractCachedTileSourceLayer<CogTileSource> {

    private static final String CACHE_NAME = "cog";

    private final CogReader reader;
    private final CogTileSource tileSource;
    private volatile CogTileLoader cogLoader;

    public CogImageryLayer(ImageryInfo info, CogReader reader) {
        super(info);
        this.reader = reader;
        this.tileSource = new CogTileSource(info, reader.info());
    }

    @Override
    protected CogTileSource getTileSource() {
        return tileSource;
    }

    @Override
    public Collection<String> getNativeProjections() {
        return Collections.singletonList("EPSG:3857");
    }

    @Override
    protected Class<? extends TileLoader> getTileLoaderClass() {
        return CogTileLoader.class;
    }

    @Override
    protected String getCacheName() {
        return CACHE_NAME;
    }

    /** Usine de chargeurs specifique a cette couche (pas d'override global). */
    @Override
    protected synchronized TileLoaderFactory getTileLoaderFactory() {
        return (listener, headers, minimumExpiry) -> {
            cogLoader = new CogTileLoader(listener, reader);
            return cogLoader;
        };
    }

    public CogReader getReader() {
        return reader;
    }

    @Override
    public String getToolTipText() {
        return "COG : " + info.getUrl();
    }

    @Override
    public synchronized void destroy() {
        super.destroy();
        if (cogLoader != null) {
            cogLoader.dispose();
        }
        try {
            reader.close();
        } catch (IOException e) {
            Logging.warn("josm-cog : erreur a la fermeture du lecteur COG : {0}", e);
        }
    }
}
