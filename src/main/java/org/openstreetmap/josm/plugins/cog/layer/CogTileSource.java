package org.openstreetmap.josm.plugins.cog.layer;

import java.awt.Point;
import java.util.Locale;

import org.openstreetmap.gui.jmapviewer.Coordinate;
import org.openstreetmap.gui.jmapviewer.OsmMercator;
import org.openstreetmap.gui.jmapviewer.Projected;
import org.openstreetmap.gui.jmapviewer.Tile;
import org.openstreetmap.gui.jmapviewer.TileRange;
import org.openstreetmap.gui.jmapviewer.TileXY;
import org.openstreetmap.gui.jmapviewer.interfaces.ICoordinate;
import org.openstreetmap.gui.jmapviewer.interfaces.IProjected;
import org.openstreetmap.gui.jmapviewer.tilesources.AbstractTMSTileSource;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.plugins.cog.crs.Crs;
import org.openstreetmap.josm.plugins.cog.crs.Geographic;
import org.openstreetmap.josm.plugins.cog.crs.Projection;
import org.openstreetmap.josm.plugins.cog.reader.CogInfo;
import org.openstreetmap.josm.plugins.cog.reader.CogReader;
import org.openstreetmap.josm.plugins.cog.reader.CustomCrs;
import org.openstreetmap.josm.plugins.cog.reader.MercatorTiles;

/**
 * "COG" tile source: standard Web Mercator grid (identical to TMS/OSM), but
 * with no tile URL: images are synthesized on the fly by
 * {@link CogTileLoaderJob} from the COG.
 */
public class CogTileSource extends AbstractTMSTileSource {

    private static final int MAX_ZOOM = 24;

    private final CogInfo cogInfo;
    private final CogReader reader;
    private final OsmMercator mercator;
    private final int minZoom;
    private final int maxZoom;
    /** COG projection (resolved on demand, null for 3857/4326 on the linear path). */
    private volatile Projection projection;
    /** Error message when the COG's custom system could not be resolved (null otherwise). */
    private volatile String crsError;

    public CogTileSource(ImageryInfo info, CogReader reader) {
        super(info);
        this.reader = reader;
        this.cogInfo = reader.info();
        info.setTileSize(256);
        this.mercator = new OsmMercator(info.getTileSize());
        this.minZoom = 0;
        this.maxZoom = MAX_ZOOM;
        if (cogInfo.crsCode != 3857 && cogInfo.crsCode != 4326) {
            // resolve the COG's system as soon as the file is opened: a registered
            // EPSG code known to the registry, otherwise the "user-defined" GeoKeys
            // (32767, or an unlisted code accompanied by its explicit parameters)
            projection = Crs.resolve(cogInfo.crsCode, cogInfo.customCrs);
            if (projection == null) {
                crsError = crsErrorReason();
            }
        }
    }

    /** Reason why resolving the system failed (for the error message). */
    private String crsErrorReason() {
        if (cogInfo.customCrs != null) {
            try {
                Crs.fromCustom(cogInfo.customCrs);
                return "EPSG:" + cogInfo.crsCode + " not supported";
            } catch (IllegalArgumentException e) {
                String prefix = cogInfo.crsCode == 0 || cogInfo.crsCode == CustomCrs.USER_DEFINED
                        ? "" : "EPSG:" + cogInfo.crsCode + " not supported, fallback on GeoKeys parameters failed: ";
                return prefix + e.getMessage();
            }
        }
        return cogInfo.crsCode == 0 ? "missing coordinate reference system" : "EPSG:" + cogInfo.crsCode + " not supported";
    }

    public CogInfo getCogInfo() {
        return cogInfo;
    }

    /** COG reader attached to this source (used by CogTileLoaderJob). */
    public CogReader getReader() {
        return reader;
    }

    /**
     * Projection of the COG's coordinate reference system (EPSG code or custom GeoKeys),
     * or null when the system is missing or not supported (see {@link #getCrsError()}).
     */
    public Projection getProjection() {
        return projection;
    }

    /** Reason why the projection is missing (null when the COG is usable). */
    public String getCrsError() {
        return crsError;
    }

    /** True when the model coordinates are degrees (EPSG:4326 or custom geographic with PM=0). */
    public boolean isGeographicDegrees() {
        return cogInfo.crsCode == 4326
                || (projection instanceof Geographic g && g.epsg() == 0 && g.isPrimeMeridianZero());
    }

    @Override
    public int getMinZoom() {
        return minZoom;
    }

    @Override
    public int getMaxZoom() {
        return maxZoom;
    }

    /** Symbolic URL: no tile is ever downloaded by URL. */
    @Override
    public String getTileUrl(int zoom, int x, int y) {
        return "cog://" + getId() + "/" + zoom + "/" + x + "/" + y;
    }

    @Override
    public String getTileId(int zoom, int x, int y) {
        return getId() + "/" + zoom + "/" + x + "/" + y;
    }

    // ------------------------------------------------------------ projections

    @Override
    public String getServerCRS() {
        return "EPSG:3857";
    }

    @Override
    public double getDistance(double lat1, double lon1, double lat2, double lon2) {
        return mercator.getDistance(lon1, lat1, lon2, lat2);
    }

    @Override
    public Point latLonToXY(double lat, double lon, int zoom) {
        return new Point((int) Math.round(mercator.lonToX(lon, zoom)),
                (int) Math.round(mercator.latToY(lat, zoom)));
    }

    @Override
    public ICoordinate xyToLatLon(int x, int y, int zoom) {
        return new Coordinate(mercator.yToLat(y, zoom), mercator.xToLon(x, zoom));
    }

    @Override
    public TileXY latLonToTileXY(double lat, double lon, int zoom) {
        int size = getTileSize();
        return new TileXY(Math.floor(mercator.lonToX(lon, zoom) / size),
                Math.floor(mercator.latToY(lat, zoom) / size));
    }

    @Override
    public ICoordinate tileXYToLatLon(int zoom, int x, int y) {
        int size = getTileSize();
        return xyToLatLon(x * size, y * size, zoom);
    }

    @Override
    public IProjected tileXYtoProjected(int x, int y, int zoom) {
        ICoordinate corner = tileXYToLatLon(zoom, x, y);
        return new Projected(MercatorTiles.lonToX(corner.getLon()),
                MercatorTiles.latToY(corner.getLat()));
    }

    @Override
    public TileXY projectedToTileXY(IProjected p, int zoom) {
        double n = Math.pow(2, zoom);
        double xf = (p.getEast() + MercatorTiles.HALF_CIRCUMFERENCE) / (2 * MercatorTiles.HALF_CIRCUMFERENCE) * n;
        double yf = (MercatorTiles.HALF_CIRCUMFERENCE - p.getNorth()) / (2 * MercatorTiles.HALF_CIRCUMFERENCE) * n;
        if (xf < 0) {
            xf = 0;
        }
        if (yf < 0) {
            yf = 0;
        }
        if (xf >= n) {
            xf = n - 1e-9;
        }
        if (yf >= n) {
            yf = n - 1e-9;
        }
        return new TileXY(xf, yf);
    }

    // ------------------------------------------------------------ tiles

    @Override
    public int getTileXMin(int zoom) {
        return 0;
    }

    @Override
    public int getTileXMax(int zoom) {
        return (int) Math.max(0, Math.ceil(mercator.getMaxPixels(zoom) / (double) getTileSize()) - 1);
    }

    @Override
    public int getTileYMin(int zoom) {
        return 0;
    }

    @Override
    public int getTileYMax(int zoom) {
        return getTileXMax(zoom);
    }

    @Override
    public boolean isInside(Tile inside, Tile outer) {
        if (inside == null || outer == null || inside.getZoom() < outer.getZoom()) {
            return false;
        }
        long diff = (long) inside.getZoom() - outer.getZoom();
        long scale = 1L << diff;
        return inside.getXtile() >= (long) outer.getXtile() * scale
                && inside.getXtile() < ((long) outer.getXtile() + 1) * scale
                && inside.getYtile() >= (long) outer.getYtile() * scale
                && inside.getYtile() < ((long) outer.getYtile() + 1) * scale;
    }

    @Override
    public TileRange getCoveringTileRange(Tile tile, int zoom) {
        if (zoom < tile.getZoom()) {
            int d = tile.getZoom() - zoom;
            return new TileRange(new TileXY(tile.getXtile() >> d, tile.getYtile() >> d),
                    new TileXY((tile.getXtile() + 1) >> d, (tile.getYtile() + 1) >> d), zoom);
        } else {
            int d = zoom - tile.getZoom();
            return new TileRange(new TileXY((long) tile.getXtile() << d, (long) tile.getYtile() << d),
                    new TileXY(((long) tile.getXtile() + 1 << d) - 1, ((long) tile.getYtile() + 1 << d) - 1), zoom);
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "CogTileSource[%s, %d levels, %s]",
                getId(), cogInfo.levelCount(), cogInfo.crsDescription());
    }
}
