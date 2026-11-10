package org.openstreetmap.josm.plugins.cog.reader;

import java.io.IOException;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Recompose une tuile d'affichage (cible {@code targetSize} x {@code targetSize})
 * en choisissant le niveau de pyramide le plus adequat, en telechargeant via la
 * {@link CogReader} uniquement les tuiles sources intersectant la boite.
 */
public final class CogComposer {

    /** Limite de garde : evite de rassembler une fenetre source demesuree. */
    private static final int MAX_WINDOW_PX = 4096;

    private CogComposer() {
    }

    public static BufferedImage compose(CogReader reader, Bounds bbox, int targetSize) throws IOException {
        CogInfo ci = reader.info();
        if (bbox.isEmpty()) {
            return null;
        }
        if (!bbox.intersects(ci.extentMinX(), ci.extentMinY(), ci.extentMaxX(), ci.extentMaxY())) {
            return null;
        }
        double wantedRes = Math.max(bbox.width(), bbox.height()) / targetSize;
        int levelIndex = ci.bestLevelFor(wantedRes);
        return compose(reader, bbox, targetSize, levelIndex);
    }

    public static BufferedImage compose(CogReader reader, Bounds bbox, int targetSize, int levelIndex) throws IOException {
        CogInfo ci = reader.info();
        CogInfo.Level level = ci.level(levelIndex);
        double res = ci.resolution(levelIndex);

        int px0 = clampTop((int) Math.floor((bbox.minX() - ci.originX) / res), level.width);
        int px1 = clampTop((int) Math.ceil((bbox.maxX() - ci.originX) / res), level.width);
        int py0 = clampTop((int) Math.floor((ci.originY - bbox.maxY()) / res), level.height);
        int py1 = clampTop((int) Math.ceil((ci.originY - bbox.minY()) / res), level.height);
        if (px1 <= px0 || py1 <= py0) {
            return null;
        }
        int windowW = Math.min(px1 - px0, MAX_WINDOW_PX);
        int windowH = Math.min(py1 - py0, MAX_WINDOW_PX);

        BufferedImage window = new BufferedImage(windowW, windowH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = window.createGraphics();
        try {
            int tx0 = px0 / level.tileWidth;
            int tx1 = (px0 + windowW - 1) / level.tileWidth;
            int ty0 = py0 / level.tileHeight;
            int ty1 = (py0 + windowH - 1) / level.tileHeight;
            for (int ty = ty0; ty <= ty1; ty++) {
                for (int tx = tx0; tx <= tx1; tx++) {
                    if (!level.hasTile(tx, ty)) {
                        continue;
                    }
                    BufferedImage tile = reader.getTile(levelIndex, tx, ty);
                    g.drawImage(tile, tx * level.tileWidth - px0, ty * level.tileHeight - py0, null);
                }
            }
        } finally {
            g.dispose();
        }

        BufferedImage out = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(window, 0, 0, targetSize, targetSize, null);
        } finally {
            g2.dispose();
        }
        return out;
    }

    private static int clampTop(int v, int limit) {
        return Math.max(0, Math.min(v, limit));
    }
}
