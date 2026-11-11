package org.openstreetmap.josm.plugins.cog.reader;

/**
 * Rectangular bounding box in the projection system of the COG
 * (metres for 3857, degrees for 4326).
 */
public record Bounds(double minX, double minY, double maxX, double maxY) {

    public double width() {
        return maxX - minX;
    }

    public double height() {
        return maxY - minY;
    }

    public boolean intersects(double x0, double y0, double x1, double y1) {
        return minX < x1 && maxX > x0 && minY < y1 && maxY > y0;
    }

    public boolean isEmpty() {
        return width() <= 0 || height() <= 0;
    }
}
