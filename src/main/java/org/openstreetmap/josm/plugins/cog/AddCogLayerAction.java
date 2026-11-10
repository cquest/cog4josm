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
            if (!org.openstreetmap.josm.plugins.cog.crs.Crs.isSupported(info.crsCode)) {
                JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                        tr("Projection EPSG:{0} non supportee. Systemes pris en charge : "
                                + "EPSG:4326, EPSG:3857, fuseaux UTM WGS84 (32601-32660, 32701-32760) "
                                + "et EPSG:2154 (Lambert-93).", info.crsCode),
                        tr("Unsupported projection"), JOptionPane.WARNING_MESSAGE);
                reader.close();
                return;
            }
            String fileName = url.substring(url.lastIndexOf('/') + 1);
            String name = "COG " + (fileName.isEmpty() ? url : fileName);
            // type d'imagerie par defaut : la couche est construite directement
            // par le plugin, le type n'est pas utilise par JOSM ici.
            ImageryInfo imageryInfo = new ImageryInfo(name, url);
            imageryInfo.setAttributionText(tr("COG : ") + url);
            CogImageryLayer layer = new CogImageryLayer(imageryInfo, reader);
            MainApplication.getLayerManager().addLayer(layer);
            // differe le zoom : le cadre de carte peut etre en cours de creation
            // (taille nulle) juste apres addLayer.
            final org.openstreetmap.josm.data.Bounds zoomBounds = latLonBounds(info);
            javax.swing.Timer zoomTimer = new javax.swing.Timer(700, e2 -> {
                if (MainApplication.getMap() != null) {
                    MainApplication.getMap().mapView.zoomTo(zoomBounds);
                }
            });
            zoomTimer.setRepeats(false);
            zoomTimer.start();
            Logging.info("josm-cog : couche ajoutee pour {0} ({1} niveaux)", url, info.levelCount());
        } catch (IOException | IllegalArgumentException ex) {
            Logging.warn("josm-cog : ouverture refusee pour {0}", url);
            Logging.warn(ex);
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

    /**
     * Emprise geographique (lat/lon) du COG : la boite de ses coordonnees modeles
     * est echantillonnee (coins + milieux des bords) puis deprojetee, ce qui suit
     * la courbure des meridien/paralleles pour les systemes projetes.
     */
    static org.openstreetmap.josm.data.Bounds latLonBounds(CogInfo info) {
        CogInfo.Level l0 = info.level(0);
        double minX = info.originX;
        double minY = info.originY - l0.height * info.scaleY;
        double maxX = info.originX + l0.width * info.scaleX;
        double maxY = info.originY;
        org.openstreetmap.josm.plugins.cog.crs.Projection proj =
                org.openstreetmap.josm.plugins.cog.crs.Crs.forEpsg(info.crsCode);
        if (proj == null) {
            return new org.openstreetmap.josm.data.Bounds(-90, -180, 90, 180);
        }
        double midX = (minX + maxX) / 2;
        double midY = (minY + maxY) / 2;
        double[][] pts = {
            {minX, minY}, {midX, minY}, {maxX, minY},
            {minX, midY}, {maxX, midY},
            {minX, maxY}, {midX, maxY}, {maxX, maxY},
        };
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (double[] p : pts) {
            double[] ll = proj.unproject(p[0], p[1]);
            minLat = Math.min(minLat, ll[0]);
            maxLat = Math.max(maxLat, ll[0]);
            minLon = Math.min(minLon, ll[1]);
            maxLon = Math.max(maxLon, ll[1]);
        }
        return new org.openstreetmap.josm.data.Bounds(minLat, minLon, maxLat, maxLon);
    }
}