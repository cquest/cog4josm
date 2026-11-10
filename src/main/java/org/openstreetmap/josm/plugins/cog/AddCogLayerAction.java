package org.openstreetmap.josm.plugins.cog;

import java.awt.event.ActionEvent;
import java.io.IOException;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.plugins.cog.layer.CogImageryLayer;
import org.openstreetmap.josm.plugins.cog.reader.CogInfo;
import org.openstreetmap.josm.plugins.cog.reader.CogReader;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Logging;

import static org.openstreetmap.josm.tools.I18n.tr;

/**
 * Demande une URL de COG, verifie qu'elle est lisible par byte-ranges et
 * ajoute la couche d'arriere-plan correspondante.
 */
public class AddCogLayerAction extends JosmAction {

    public AddCogLayerAction() {
        super(tr("Add remote COG (GeoTIFF) background layer…"),
                new ImageProvider("cog"),
                tr("Add a background layer reading a Cloud Optimized GeoTIFF via HTTP byte-ranges"),
                null, false, null, false);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        String url = JOptionPane.showInputDialog(MainApplication.getMainFrame(),
                tr("URL of the COG (Cloud Optimized GeoTIFF):"),
                tr("Add COG background layer"),
                JOptionPane.QUESTION_MESSAGE);
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        url = url.trim();
        CogReader reader = null;
        try {
            reader = CogReader.open(url);
            CogInfo info = reader.info();
            if (info.crsCode != 3857 && info.crsCode != 4326) {
                JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                        tr("Projection EPSG:{0} non supportee (reprojection prevue en phase 2 ; "
                                + "seuls EPSG:3857 et EPSG:4326 sont acceptes pour l''instant).", info.crsCode),
                        tr("Unsupported projection"), JOptionPane.WARNING_MESSAGE);
                reader.close();
                return;
            }
            String fileName = url.substring(url.lastIndexOf('/') + 1);
            String name = "COG " + (fileName.isEmpty() ? url : fileName);
            ImageryInfo imageryInfo = new ImageryInfo(name, url, "cog",
                    tr("Remote COG (Cloud Optimized GeoTIFF)"), "");
            imageryInfo.setAttributionText(tr("COG : ") + url);
            CogImageryLayer layer = new CogImageryLayer(imageryInfo, reader);
            MainApplication.getLayerManager().addLayer(layer);
            Logging.info("josm-cog : couche ajoutee pour {0} ({1} niveaux)", url, info.levelCount());
        } catch (IOException | IllegalArgumentException ex) {
            Logging.warn("josm-cog : echec d'ouverture {0} : {1}", url, ex);
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    tr("Cannot open this COG :") + "\n" + ex.getMessage(),
                    tr("Error"), JOptionPane.ERROR_MESSAGE);
        }
    }
}
