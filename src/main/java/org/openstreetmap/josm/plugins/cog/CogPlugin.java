package org.openstreetmap.josm.plugins.cog;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;

/**
 * Plugin JOSM "cog" : affiche en fond des images distantes au format COG
 * (Cloud Optimized GeoTIFF) lues par HTTP byte-ranges.
 */
public class CogPlugin extends Plugin {

    public static final String PLUGIN_NAME = "cog";

    private final AddCogLayerAction addCogLayerAction = new AddCogLayerAction();

    public CogPlugin(PluginInformation info) {
        super(info);
        MainApplication.getMenu().imagerySubMenu.add(addCogLayerAction);
    }

    /** Action d'ajout d'une couche COG (referencement pour tests/outils). */
    public AddCogLayerAction getAddCogLayerAction() {
        return addCogLayerAction;
    }
}
