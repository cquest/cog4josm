package org.openstreetmap.josm.plugins.cog.reader;

import java.util.Locale;

/**
 * Black-to-alpha transparency (phase 3), applied only to RGB sources that
 * carry no alpha channel (see {@link CogInfo.Level#isRgbNoAlpha()}). Grey
 * levels and levels that already have alpha (ExtraSamples) are never touched.
 *
 * <p>The policy is carried by the {@link CogReader}; {@link TileDecoder}
 * turns matching pixels fully transparent while decoding, so the alpha
 * simply propagates through the composer.</p>
 */
public enum TransparencyPolicy {

    /** No transparency: black pixels stay opaque (pre-0.5.0 behaviour). */
    NONE,
    /** Transparent only when the GDAL_NODATA tag (42113) declares black. */
    METADATA,
    /**
     * Default: any near-black pixel of an RGB/RGB-JPEG level becomes
     * transparent (see {@link #NEAR_BLACK_THRESHOLD}).
     */
    BLACK_NEAR;

    /**
     * A pixel is "near black" (and gets alpha 0) when its three channels are
     * all at most this value. The slack of 8 absorbs the JPEG ringing around
     * truly black areas (chroma subsampling moves (0,0,0) edges to values
     * around 1..5) without eating dark-but-intentional content.
     */
    public static final int NEAR_BLACK_THRESHOLD = 8;

    /** True when pixels (r,g,b) each at most {@link #NEAR_BLACK_THRESHOLD}. */
    public static boolean nearBlack(int r, int g, int b) {
        return r <= NEAR_BLACK_THRESHOLD && g <= NEAR_BLACK_THRESHOLD && b <= NEAR_BLACK_THRESHOLD;
    }

    /**
     * True when this policy makes black pixels of {@code level} transparent:
     * the level must be RGB without alpha, and under {@link #METADATA} the
     * GDAL_NODATA string of the file must declare black.
     */
    public boolean makesBlackTransparent(CogInfo.Level level, String noData) {
        if (this == NONE || !level.isRgbNoAlpha()) {
            return false;
        }
        return this == BLACK_NEAR || noDataMeansBlack(noData);
    }

    /**
     * True when a GDAL_NODATA value (TIFF tag 42113, ASCII, one number per
     * band) declares black: every band is zero ("0", "0 0 0", "0, 0, 0").
     * Anything else — "-9999", "nan", empty, missing — is not black.
     */
    static boolean noDataMeansBlack(String noData) {
        if (noData == null) {
            return false;
        }
        String[] tokens = noData.trim().split("[\\s,;]+");
        if (tokens.length == 0 || (tokens.length == 1 && tokens[0].isEmpty())) {
            return false;
        }
        for (String t : tokens) {
            double v;
            try {
                v = Double.parseDouble(t);
            } catch (NumberFormatException e) {
                return false;
            }
            if (v != 0.0) {
                return false;
            }
        }
        return true;
    }

    /** Key of this policy in the JOSM "cog.transparency" preference. */
    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a preference value; unknown values fall back to the default. */
    public static TransparencyPolicy fromConfigName(String value) {
        if (value == null) {
            return BLACK_NEAR;
        }
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "none":
            case "off":
                return NONE;
            case "metadata":
                return METADATA;
            default:
                return BLACK_NEAR;
        }
    }
}
