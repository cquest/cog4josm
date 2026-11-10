package org.openstreetmap.josm.plugins.cog.reader;

/**
 * Boite englobante rectangulaire dans le systeme de projection du COG
 * (metres pour un 3857, degres pour un 4326).
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
