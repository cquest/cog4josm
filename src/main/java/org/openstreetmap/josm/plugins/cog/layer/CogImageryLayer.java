package org.openstreetmap.josm.plugins.cog.layer;

import java.awt.event.ActionEvent;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import javax.swing.AbstractAction;
import javax.swing.Action;

import org.apache.commons.jcs3.access.behavior.ICacheAccess;
import org.openstreetmap.gui.jmapviewer.interfaces.TileLoader;
import org.openstreetmap.josm.data.cache.BufferedImageCacheEntry;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.data.imagery.TileLoaderFactory;
import org.openstreetmap.josm.gui.layer.AbstractCachedTileSourceLayer;
import org.openstreetmap.josm.plugins.cog.reader.CogReader;
import org.openstreetmap.josm.plugins.cog.reader.TransparencyPolicy;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Logging;

import static org.openstreetmap.josm.tools.I18n.tr;

/**
 * Background layer displaying a remote COG. Reuses all the machinery of JOSM
 * imagery layers (cache, zoom, transparency, filters); only tile production is
 * custom ({@link CogTileLoader}). Composed display tiles are kept in the JCS
 * region named {@link #CACHE_NAME} (JOSM memory and disk tile caches), exactly
 * like a TMS layer keeps downloaded images there.
 */
public class CogImageryLayer extends AbstractCachedTileSourceLayer<CogTileSource> {

    /** JCS region of composed display tiles (shared with the JOSM cache manager). */
    private static final String CACHE_NAME = "cog";

    /**
     * JOSM preference holding the default transparency policy for newly
     * opened COG layers: "black_near" (default), "metadata" or "none".
     */
    static final String TRANSPARENCY_PREF_KEY = "cog.transparency";

    private final CogReader reader;
    private final CogTileSource tileSource;
    private volatile CogTileLoader cogLoader;

    public CogImageryLayer(ImageryInfo info, CogReader reader) {
        super(info);
        this.reader = reader;
        reader.setTransparencyPolicy(defaultTransparencyPolicy());
        this.tileSource = new CogTileSource(info, reader);
    }

    /** Default policy for a new layer: the "cog.transparency" preference. */
    private static TransparencyPolicy defaultTransparencyPolicy() {
        try {
            return TransparencyPolicy.fromConfigName(
                    Config.getPref().get(TRANSPARENCY_PREF_KEY, TransparencyPolicy.BLACK_NEAR.configName()));
        } catch (Throwable t) { // NOSONAR: preferences are unavailable in some test setups
            return TransparencyPolicy.BLACK_NEAR;
        }
    }

    /**
     * Layer menu: the base entries plus a cycling "Transparency of black
     * pixels" entry (off → metadata only → near black → off). Changing it
     * re-decodes through the reader (each policy keeps its own decoded
     * tiles), drops this layer's composed tile cache and repaints; the
     * choice also becomes the default for layers opened later.
     */
    @Override
    public Action[] getMenuEntries() {
        Action[] base = super.getMenuEntries();
        Action[] entries = Arrays.copyOf(base, base.length + 1);
        entries[base.length] = new AbstractAction(transparencyMenuItemLabel()) {
            @Override
            public void actionPerformed(ActionEvent e) {
                TransparencyPolicy next;
                switch (reader.transparencyPolicy()) {
                    case METADATA:
                        next = TransparencyPolicy.NONE;
                        break;
                    case NONE:
                        next = TransparencyPolicy.BLACK_NEAR;
                        break;
                    default:
                        next = TransparencyPolicy.METADATA;
                        break;
                }
                reader.setTransparencyPolicy(next);
                try {
                    Config.getPref().put(TRANSPARENCY_PREF_KEY, next.configName());
                } catch (Throwable t) { // NOSONAR: a missing preference must not break the toggle
                    Logging.warn("cannot remember the transparency policy: {0}", t);
                }
                putValue(Action.NAME, transparencyMenuItemLabel());
                clearTileCache();
                invalidate();
            }
        };
        return entries;
    }

    private String transparencyMenuItemLabel() {
        String state;
        switch (reader.transparencyPolicy()) {
            case NONE:
                state = tr("off");
                break;
            case METADATA:
                state = tr("metadata only");
                break;
            default:
                state = tr("on (near black)");
                break;
        }
        return tr("Transparency of black pixels") + ": " + state;
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

    /** Tile loader factory specific to this layer (no global override). */
    @Override
    protected synchronized TileLoaderFactory getTileLoaderFactory() {
        return (listener, headers, minimumExpiry) -> {
            cogLoader = new CogTileLoader(listener, tileCacheRegion());
            return cogLoader;
        };
    }

    /**
     * The layer's JCS region (the very one AbstractCachedTileSourceLayer
     * would use): composed display tiles go there, so JOSM's memory
     * management, disk tile cache and "purge tile cache" action all apply.
     * Null when JCS is unavailable: tiles are then only kept in the
     * JMapViewer tile list, like before.
     */
    private static ICacheAccess<String, BufferedImageCacheEntry> tileCacheRegion() {
        try {
            return getCache(CACHE_NAME);
        } catch (Throwable t) { // NOSONAR: caching is optional, never break the layer
            Logging.warn(": tile cache region unavailable, composed tiles will not be kept: {0}", t);
            return null;
        }
    }

    public CogReader getReader() {
        return reader;
    }

    @Override
    public String getToolTipText() {
        return "COG: " + info.getUrl();
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
            Logging.warn("josm-cog: error while closing the COG reader: {0}", e);
        }
    }
}
