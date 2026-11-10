package org.openstreetmap.josm.plugins.cog;

import org.openstreetmap.josm.data.Version;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.tools.Logging;

/**
 * Plugin JOSM "cog" : affiche en fond des images distantes au format COG
 * (Cloud Optimized GeoTIFF) lues par HTTP byte-ranges.
 */
public class CogPlugin extends Plugin {

    public static final String PLUGIN_NAME = "cog";

    private final AddCogLayerAction addCogLayerAction = new AddCogLayerAction();
    private boolean menuEntryAdded;

    public CogPlugin(PluginInformation info) {
        super(info);
        Logging.info("josm-cog : plugin {0} charge (JOSM {1})", PLUGIN_NAME,
                Version.getInstance().getVersionString());
        registerMenuEntry();
    }

    /**
     * Tentative d'ajout de l'entree au menu Imagery ; silencieuse si le menu
     * n'est pas encore cree (sera retente a l'initialisation du MapFrame).
     */
    private synchronized void registerMenuEntry() {
        if (menuEntryAdded) {
            return;
        }
        if (MainApplication.getMenu() == null) {
            Logging.info("josm-cog : menu principal pas encore pret, report a l'ouverture de la carte");
            return;
        }
        MainApplication.getMenu().imagerySubMenu.add(addCogLayerAction);
        menuEntryAdded = true;
        Logging.info("josm-cog : entree ajoutee au menu Imagery (fin de liste / sous-menu Plus...)");
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (newFrame != null) {
            registerMenuEntry();
        }
    }

    /** Action d'ajout d'une couche COG (referencement pour tests/outils). */
    public AddCogLayerAction getAddCogLayerAction() {
        return addCogLayerAction;
    }
}
