package org.openstreetmap.josm.plugins.cog;

import org.openstreetmap.josm.data.Version;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
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
        registerMenuEntry();
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
