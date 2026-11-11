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
 * Asks for a COG URL, checks it is readable through byte-ranges and adds the
 * matching background layer.
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
            org.openstreetmap.josm.plugins.cog.crs.Projection proj = resolveProjection(info);
            if (proj == null) {
                String why;
                if (org.openstreetmap.josm.plugins.cog.crs.Crs.isSupported(info.crsCode)) {
                    why = tr("supported projection but resolution failed");
                } else if (info.customCrs != null && info.crsCode != 0
                        && info.crsCode != org.openstreetmap.josm.plugins.cog.reader.CustomCrs.USER_DEFINED) {
                    why = tr("Unknown EPSG:") + " " + info.crsCode + " "
                            + tr("to the registry, and its GeoKeys parameters fallback failed: ")
                            + tryCustom(info);
                } else {
                    why = info.crsCode == 0
                            || info.crsCode == org.openstreetmap.josm.plugins.cog.reader.CustomCrs.USER_DEFINED
                            ? tryCustom(info)
                            : tr("Unknown EPSG:") + " " + info.crsCode;
                }
                JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                        tr("Unsupported coordinate reference system for this COG") + " (" + info.crsDescription() + ").\n"
                                + tr("Reason: ") + why + "\n\n"
                                + tr("Supported systems: EPSG:4326, EPSG:3857, "
                                        + "WGS84 UTM zones (32601-32660, 32701-32760), "
                                        + "EPSG:2154 (Lambert-93), EPSG:3942-3950 "
                                        + "(Lambert-93 zones CC42-CC50), "
                                        + "and GeoTIFF \"user-defined\" systems with explicit parameters "
                                        + "(geographic, transverse Mercator, Lambert conformal conic)."),
                        tr("Unsupported projection"), JOptionPane.WARNING_MESSAGE);
                reader.close();
                return;
            }
            String fileName = url.substring(url.lastIndexOf('/') + 1);
            String name = "COG " + (fileName.isEmpty() ? url : fileName);
            // default imagery type: the layer is built by the plugin itself,
            // JOSM does not use the type here.
            ImageryInfo imageryInfo = new ImageryInfo(name, url);
            imageryInfo.setAttributionText(tr("COG: ") + url);
            CogImageryLayer layer = new CogImageryLayer(imageryInfo, reader);
            MainApplication.getLayerManager().addLayer(layer);
            // zoom is deferred: the map frame may still be under construction
            // (zero size) right after addLayer.
            final org.openstreetmap.josm.data.Bounds zoomBounds = latLonBounds(info, proj);
            javax.swing.Timer zoomTimer = new javax.swing.Timer(700, e2 -> {
                if (MainApplication.getMap() != null) {
                    MainApplication.getMap().mapView.zoomTo(zoomBounds);
                }
            });
            zoomTimer.setRepeats(false);
            zoomTimer.start();
            Logging.info("josm-cog: layer added for {0} ({1} levels)", url, info.levelCount());
        } catch (IOException | RuntimeException ex) {
            // RuntimeException: a hostile file could still escape a hardening
            // path (array bounds, parse): it must show an error dialog, never
            // an uncaught exception on the EDT
            Logging.warn("josm-cog: refused opening for {0}", url);
            Logging.warn(ex);
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                    tr("Cannot open this COG:") + "\n" + ex.getMessage(),
                    tr("Error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Projection of the coordinate reference system declared by the COG
     * (registered EPSG code known to the registry, otherwise "user-defined"
     * GeoKeys 32767 or explicit parameters attached to an unlisted code),
     * null when unresolvable.
     */
    static org.openstreetmap.josm.plugins.cog.crs.Projection resolveProjection(CogInfo info) {
        return org.openstreetmap.josm.plugins.cog.crs.Crs.resolve(info.crsCode, info.customCrs);
    }

    /** Failure reason of the custom system resolution (for the error message). */
    private static String tryCustom(CogInfo info) {
        if (info.customCrs == null) {
            return tr("no usable coordinate reference system GeoKey");
        }
        try {
            org.openstreetmap.josm.plugins.cog.crs.Crs.fromCustom(info.customCrs);
            return tr("resolved on second attempt (inconsistent)");
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /**
     * Geographic (lat/lon) extent of the COG: its model-coordinate box is
     * sampled (corners + edge midpoints) then unprojected, which follows the
     * curvature of meridians/parallels for projected systems.
     */
    static org.openstreetmap.josm.data.Bounds latLonBounds(CogInfo info) {
        return latLonBounds(info, resolveProjection(info));
    }

    static org.openstreetmap.josm.data.Bounds latLonBounds(CogInfo info,
            org.openstreetmap.josm.plugins.cog.crs.Projection proj) {
        if (proj == null) {
            return new org.openstreetmap.josm.data.Bounds(-90, -180, 90, 180);
        }
        CogInfo.Level l0 = info.level(0);
        double minX = info.originX;
        double minY = info.originY - l0.height * info.scaleY;
        double maxX = info.originX + l0.width * info.scaleX;
        double maxY = info.originY;
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
