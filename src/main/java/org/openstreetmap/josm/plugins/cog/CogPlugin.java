package org.openstreetmap.josm.plugins.cog;

import java.io.File;

import org.openstreetmap.josm.data.Version;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.cog.reader.FileMetadataStore;
import org.openstreetmap.josm.plugins.cog.reader.MetadataCache;
import org.openstreetmap.josm.spi.preferences.IBaseDirectories;
import org.openstreetmap.josm.tools.Logging;

/**
 * JOSM "cog" plugin: displays remote images in the COG (Cloud Optimized
 * GeoTIFF) format as a background layer, read over HTTP byte-ranges.
 */
public class CogPlugin extends Plugin {

    public static final String PLUGIN_NAME = "cog";

    private final AddCogLayerAction addCogLayerAction = new AddCogLayerAction();
    private boolean menuEntryAdded;

    public CogPlugin(PluginInformation info) {
        super(info);
        Logging.info("josm-cog: plugin {0} loaded (JOSM {1})", PLUGIN_NAME,
                Version.getInstance().getVersionString());
        attachMetadataStore();
        registerMenuEntry();
    }

    /**
     * Enables cross-session persistence of COG metadata (phase 2.8): the
     * global {@link MetadataCache} gains a disk store in the plugin cache
     * directory, so reopening a layer revalidates with one conditional
     * request instead of re-downloading the header blocks. Best effort:
     * without a store the plugin simply works in memory only.
     */
    private void attachMetadataStore() {
        try {
            IBaseDirectories dirs = getPluginDirs();
            File cacheDir = dirs == null ? null : dirs.getCacheDirectory(true);
            if (cacheDir == null) {
                return;
            }
            MetadataCache.global().attachStore(
                    new FileMetadataStore(cacheDir.toPath(), PLUGIN_NAME));
            Logging.info("josm-cog: COG metadata store enabled in {0}", cacheDir);
        } catch (RuntimeException e) {
            Logging.warn("josm-cog: metadata store unavailable, in-memory only");
            Logging.warn(e);
        }
    }

    /**
     * Attempts to add the entry to the Imagery menu; stays silent when the menu
     * has not been created yet (it is tried again when the MapFrame is initialized).
     */
    private synchronized void registerMenuEntry() {
        if (menuEntryAdded) {
            return;
        }
        if (MainApplication.getMenu() == null) {
            Logging.info("josm-cog: main menu not ready yet, deferred until the map is opened");
            return;
        }
        MainApplication.getMenu().imagerySubMenu.add(addCogLayerAction);
        menuEntryAdded = true;
        Logging.info("josm-cog: entry added to the Imagery menu (end of the list / More... sub-menu)");
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (newFrame != null) {
            registerMenuEntry();
        }
    }

    /** Action that adds a COG layer (exposed for tests/tools). */
    public AddCogLayerAction getAddCogLayerAction() {
        return addCogLayerAction;
    }
}
