package org.openstreetmap.josm.plugins.cog.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

class MercatorTilesTest {

    @Test
    void latLonRoundTrip() {
        double lat = 48.85, lon = 2.35;
        assertEquals(lon, MercatorTiles.xToLon(MercatorTiles.lonToX(lon)), 1e-9);
        assertEquals(lat, MercatorTiles.yToLat(MercatorTiles.latToY(lat)), 1e-9);
    }

    @Test
    void mercatorKnownValues() {
        // Valeurs absolues (EPSG:3857 officiel, R=6378137) : un facteur pi
        // commun a latToY/yToLat passerait inapercu en aller-retour seul.
        assertEquals(0.0, MercatorTiles.latToY(0.0), 1e-6);
        assertEquals(0.0, MercatorTiles.yToLat(0.0), 1e-9);
        assertEquals(6240993.463, MercatorTiles.latToY(48.80), 1.0);
        assertEquals(6261297.953, MercatorTiles.latToY(48.92), 1.0);
        assertEquals(48.80, MercatorTiles.yToLat(6240993.463), 1e-6);
        assertEquals(48.92, MercatorTiles.yToLat(6261297.953), 1e-6);
        assertEquals(250468.854, MercatorTiles.lonToX(2.25), 1.0);
        assertEquals(2.25, MercatorTiles.xToLon(250468.854), 1e-6);
        // Limites : +-180 deg <-> +-demi-circonference
        assertEquals(MercatorTiles.HALF_CIRCUMFERENCE, MercatorTiles.lonToX(180.0), 1e-6);
        assertEquals(-MercatorTiles.MAX_MERC_LAT, MercatorTiles.yToLat(-MercatorTiles.HALF_CIRCUMFERENCE), 1e-6);
    }

    @Test
    void tileBoundsAtZoom0() {
        Bounds b = MercatorTiles.tileBounds(0, 0, 0);
        assertEquals(-MercatorTiles.HALF_CIRCUMFERENCE, b.minX(), 1.0);
        assertEquals(MercatorTiles.HALF_CIRCUMFERENCE, b.maxX(), 1.0);
        assertEquals(-MercatorTiles.HALF_CIRCUMFERENCE, b.minY(), 1.0);
        assertEquals(MercatorTiles.HALF_CIRCUMFERENCE, b.maxY(), 1.0);
    }

    @Test
    void tileBoundsAdjacent() {
        Bounds a = MercatorTiles.tileBounds(5, 3, 7);
        Bounds b = MercatorTiles.tileBounds(5, 4, 7);
        assertEquals(a.maxX(), b.minX(), 1e-6);
        assertEquals(a.minY(), b.minY(), 1e-6);
        assertTrue(a.width() > 0);
    }

    @Test
    void northWestTileBounds() {
        Bounds b = MercatorTiles.tileBounds(1, 0, 0);
        assertEquals(-MercatorTiles.HALF_CIRCUMFERENCE, b.minX(), 1.0);
        assertEquals(0.0, b.maxX(), 1.0);
        assertEquals(0.0, b.minY(), 1.0);
        assertEquals(MercatorTiles.HALF_CIRCUMFERENCE, b.maxY(), 1.0);
    }
}
