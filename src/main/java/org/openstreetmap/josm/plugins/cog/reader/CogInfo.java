package org.openstreetmap.josm.plugins.cog.reader;

import java.util.List;
import java.util.Locale;

/**
 * Description of a COG: pyramid of tiled levels + simple affine
 * georeferencing (ModelTiepoint + ModelPixelScale, "north-up" image).
 * Levels are sorted from the finest (index 0) to the coarsest.
 */
public final class CogInfo {

    /** A level of the pyramid (tiled IFD). */
    public static final class Level {
        public final int index;
        public final int width;
        public final int height;
        public final int tileWidth;
        public final int tileHeight;
        /** TIFF Compression tag: 1 none, 5 LZW, 6 JPEG, 8/32946 deflate, 32773 packbits. */
        public final int compression;
        /** TIFF PhotometricInterpretation: 0 whiteIsZero, 1 blackIsZero, 2 RGB, 6 YCbCr. */
        public final int photometric;
        public final int predictor;
        public final int samplesPerPixel;
        public final int[] bitsPerSample;
        public final int[] extraSamples;
        public final int sampleFormat;
        /** NewSubfileType: bit 0 = reduced image (overview). */
        public final int flags;
        /** Endianness of the TIFF file (MM = true, II = false): applies to 16-bit samples. */
        public final boolean bigEndian;
        public final long[] tileOffsets;
        public final long[] tileByteCounts;
        /**
         * External JPEG tables (TIFF JPEGTables tag 347), null when missing.
         * GDAL stores the quantization tables there when the compressed JPEG
         * tiles do not embed them.
         */
        public final byte[] jpegTables;

        Level(int index, int width, int height, int tileWidth, int tileHeight,
              int compression, int photometric, int predictor,
              int samplesPerPixel, int[] bitsPerSample, int[] extraSamples,
              int sampleFormat, int flags, boolean bigEndian, long[] tileOffsets, long[] tileByteCounts) {
            this(index, width, height, tileWidth, tileHeight, compression, photometric, predictor,
                    samplesPerPixel, bitsPerSample, extraSamples, sampleFormat, flags, bigEndian,
                    tileOffsets, tileByteCounts, null);
        }

        Level(int index, int width, int height, int tileWidth, int tileHeight,
              int compression, int photometric, int predictor,
              int samplesPerPixel, int[] bitsPerSample, int[] extraSamples,
              int sampleFormat, int flags, boolean bigEndian, long[] tileOffsets, long[] tileByteCounts,
              byte[] jpegTables) {
            this.index = index;
            this.width = width;
            this.height = height;
            this.tileWidth = tileWidth;
            this.tileHeight = tileHeight;
            this.compression = compression;
            this.photometric = photometric;
            this.predictor = predictor;
            this.samplesPerPixel = samplesPerPixel;
            this.bitsPerSample = bitsPerSample;
            this.extraSamples = extraSamples;
            this.sampleFormat = sampleFormat;
            this.flags = flags;
            this.bigEndian = bigEndian;
            this.tileOffsets = tileOffsets;
            this.tileByteCounts = tileByteCounts;
            this.jpegTables = jpegTables;
        }

        Level withIndex(int i) {
            return new Level(i, width, height, tileWidth, tileHeight, compression, photometric,
                    predictor, samplesPerPixel, bitsPerSample, extraSamples, sampleFormat,
                    flags, bigEndian, tileOffsets, tileByteCounts, jpegTables);
        }

        public int numXTiles() {
            return (width + tileWidth - 1) / tileWidth;
        }

        public int numYTiles() {
            return (height + tileHeight - 1) / tileHeight;
        }

        public boolean hasTile(int tx, int ty) {
            return tx >= 0 && ty >= 0 && tx < numXTiles() && ty < numYTiles();
        }

        public long tileOffset(int tx, int ty) {
            return tileOffsets[(int) ((long) ty * numXTiles() + tx)];
        }

        public long tileByteCount(int tx, int ty) {
            return tileByteCounts[(int) ((long) ty * numXTiles() + tx)];
        }
    }

    public final List<Level> levels;
    /** EPSG code (0 = unknown/user-defined; in that case see {@link #customCrs}). */
    public final int crsCode;
    /**
     * "user-defined" coordinate reference system (GeoKeys 32767) when the EPSG
     * code is missing, null otherwise. Also null when the file declares no
     * usable CRS.
     */
    public final CustomCrs customCrs;
    /** Model coordinates of the upper-left (north-west) corner. */
    public final double originX;
    public final double originY;
    /** Size of a pixel at level 0, in model units (metres or degrees), always positive. */
    public final double scaleX;
    public final double scaleY;
    public final String noData;

    CogInfo(List<Level> levels, int crsCode, double originX, double originY,
            double scaleX, double scaleY, String noData) {
        this(levels, crsCode, null, originX, originY, scaleX, scaleY, noData);
    }

    CogInfo(List<Level> levels, int crsCode, CustomCrs customCrs, double originX, double originY,
            double scaleX, double scaleY, String noData) {
        this.levels = List.copyOf(levels);
        this.crsCode = crsCode;
        this.customCrs = customCrs;
        this.originX = originX;
        this.originY = originY;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.noData = noData;
    }

    public Level level(int i) {
        return levels.get(i);
    }

    public int levelCount() {
        return levels.size();
    }

    /** Pixel size (model units / pixel) of a given level. */
    public double resolution(int levelIndex) {
        Level l = levels.get(levelIndex);
        return scaleX * levels.get(0).width / (double) l.width;
    }

    /** Index of the level whose resolution is closest to {@code wantedResolution}. */
    public int bestLevelFor(double wantedResolution) {
        int best = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < levels.size(); i++) {
            double res = resolution(i);
            double diff = Math.abs(Math.log(res) - Math.log(wantedResolution));
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    /** Human-readable description of the coordinate reference system (for messages and logs). */
    public String crsDescription() {
        String customPart = null;
        if (customCrs != null) {
            Integer ct = customCrs.key(CustomCrs.KEY_PROJ_COORD_TRANS);
            Integer mc = customCrs.key(CustomCrs.KEY_PROJ_METHOD_CODE);
            customPart = "custom CRS (3074=" + ct + ", 3075=" + mc + ")";
        }
        if (crsCode != 0 && crsCode != CustomCrs.USER_DEFINED) {
            // registered code; the file may also carry its own GeoKeys
            // parameters (hybrid, see Crs.resolve): both are displayed
            return customPart != null ? "EPSG:" + crsCode + " + " + customPart : "EPSG:" + crsCode;
        }
        return customPart != null ? customPart : "unknown CRS";
    }

    public double extentMinX() {
        return originX;
    }

    public double extentMaxX() {
        return originX + levels.get(0).width * scaleX;
    }

    /** South (bottom edge). */
    public double extentMinY() {
        return originY - levels.get(0).height * scaleY;
    }

    /** North (top edge). */
    public double extentMaxY() {
        return originY;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "COG: %d IFD(s), %s, origin=(%.6f, %.6f), pixel=(%.9f, %.9f)%n",
                levels.size(), crsDescription(), originX, originY, scaleX, scaleY));
        sb.append(String.format(Locale.ROOT, "extent: [%.6f, %.6f, %.6f, %.6f]%n",
                extentMinX(), extentMinY(), extentMaxX(), extentMaxY()));
        for (Level l : levels) {
            sb.append(String.format(Locale.ROOT, "  level %d: %dx%d tiles %dx%d (%dx%d tiles), compression=%d photo=%d bps=%s samples=%d extra=%s%n",
                    l.index, l.width, l.height, l.tileWidth, l.tileHeight, l.numXTiles(), l.numYTiles(),
                    l.compression, l.photometric, java.util.Arrays.toString(l.bitsPerSample),
                    l.samplesPerPixel, java.util.Arrays.toString(l.extraSamples)));
        }
        return sb.toString();
    }
}
