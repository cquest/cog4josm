package org.openstreetmap.josm.plugins.cog.reader;

import java.util.List;
import java.util.Locale;

/**
 * Description d'un COG : pyramide de niveaux tuiles + georeferencement affine
 * simple (ModelTiepoint + ModelPixelScale, image "north-up").
 * Les niveaux sont tries du plus fin (index 0) au plus grossier.
 */
public final class CogInfo {

    /** Un niveau de la pyramide (IFD tuile). */
    public static final class Level {
        public final int index;
        public final int width;
        public final int height;
        public final int tileWidth;
        public final int tileHeight;
        /** TIFF Compression tag : 1 none, 5 LZW, 6 JPEG, 8/32946 deflate, 32773 packbits. */
        public final int compression;
        /** TIFF PhotometricInterpretation : 0 whiteIsZero, 1 blackIsZero, 2 RGB, 6 YCbCr. */
        public final int photometric;
        public final int predictor;
        public final int samplesPerPixel;
        public final int[] bitsPerSample;
        public final int[] extraSamples;
        public final int sampleFormat;
        /** NewSubfileType : bit 0 = reduced image (overview). */
        public final int flags;
        /** Boutisme du fichier TIFF (MM = true, II = false) : vaut pour les echantillons 16 bits. */
        public final boolean bigEndian;
        public final long[] tileOffsets;
        public final long[] tileByteCounts;

        Level(int index, int width, int height, int tileWidth, int tileHeight,
              int compression, int photometric, int predictor,
              int samplesPerPixel, int[] bitsPerSample, int[] extraSamples,
              int sampleFormat, int flags, boolean bigEndian, long[] tileOffsets, long[] tileByteCounts) {
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
        }

        Level withIndex(int i) {
            return new Level(i, width, height, tileWidth, tileHeight, compression, photometric,
                    predictor, samplesPerPixel, bitsPerSample, extraSamples, sampleFormat,
                    flags, bigEndian, tileOffsets, tileByteCounts);
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
    /** Code EPSG (0 = inconnu/user-defined). */
    public final int crsCode;
    /** Coordonnees modeles du coin superieur-gauche (coin Nord-Ouest). */
    public final double originX;
    public final double originY;
    /** Taille d'un pixel au niveau 0, en unites demodele (metres ou degres), toujours positif. */
    public final double scaleX;
    public final double scaleY;
    public final String noData;

    CogInfo(List<Level> levels, int crsCode, double originX, double originY,
            double scaleX, double scaleY, String noData) {
        this.levels = List.copyOf(levels);
        this.crsCode = crsCode;
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

    /** Taille de pixel (unites de modele / pixel) d'un niveau donne. */
    public double resolution(int levelIndex) {
        Level l = levels.get(levelIndex);
        return scaleX * levels.get(0).width / (double) l.width;
    }

    /** Index du niveau dont la resolution est la plus proche de {@code wantedResolution}. */
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

    public double extentMinX() {
        return originX;
    }

    public double extentMaxX() {
        return originX + levels.get(0).width * scaleX;
    }

    /** Sud (bord bas). */
    public double extentMinY() {
        return originY - levels.get(0).height * scaleY;
    }

    /** Nord (bord haut). */
    public double extentMaxY() {
        return originY;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "COG : %d IFD(s), EPSG:%d, origine=(%.6f, %.6f), pixel=(%.9f, %.9f)%n",
                levels.size(), crsCode, originX, originY, scaleX, scaleY));
        sb.append(String.format(Locale.ROOT, "emprise : [%.6f, %.6f, %.6f, %.6f]%n",
                extentMinX(), extentMinY(), extentMaxX(), extentMaxY()));
        for (Level l : levels) {
            sb.append(String.format(Locale.ROOT, "  niveau %d : %dx%d tuiles %dx%d (%dx%d tuiles), compression=%d photo=%d bps=%s samples=%d extra=%s%n",
                    l.index, l.width, l.height, l.tileWidth, l.tileHeight, l.numXTiles(), l.numYTiles(),
                    l.compression, l.photometric, java.util.Arrays.toString(l.bitsPerSample),
                    l.samplesPerPixel, java.util.Arrays.toString(l.extraSamples)));
        }
        return sb.toString();
    }
}
