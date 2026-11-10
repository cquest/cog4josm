package org.openstreetmap.josm.plugins.cog.reader;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import java.awt.image.BufferedImage;

/**
 * Point d'entree : ouvre un COG via une {@link ByteSource}, parse les en-tetes,
 * puis fournit les tuiles decodees avec un cache LRU en memoire.
 */
public final class CogReader implements Closeable {

    private static final int MAX_CACHED_TILES = 128;

    private final ByteSource source;
    private final CogInfo info;
    private final Map<String, BufferedImage> tileCache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > MAX_CACHED_TILES;
                }
            });

    public static CogReader open(String url) throws IOException {
        return open(url, Map.of());
    }

    public static CogReader open(String url, Map<String, String> headers) throws IOException {
        HttpRangeReader http = HttpRangeReader.open(url, headers);
        if (!http.supportsRanges()) {
            http.close();
            throw new RangeNotSupportedException(url);
        }
        return new CogReader(http);
    }

    public static CogReader open(ByteSource source) throws IOException {
        return new CogReader(source);
    }

    private CogReader(ByteSource source) throws IOException {
        this.source = source;
        this.info = TiffParser.parse(source);
    }

    public CogInfo info() {
        return info;
    }

    public ByteSource source() {
        return source;
    }

    /** Tuile decodee du niveau {@code level} a la position de tuile (tx, ty). */
    public BufferedImage getTile(int level, int tx, int ty) throws IOException {
        CogInfo.Level lvl = info.level(level);
        if (!lvl.hasTile(tx, ty)) {
            throw new IOException("Tuile hors pyramide : niveau " + level + " tuile " + tx + "," + ty
                    + " (" + lvl.numXTiles() + "x" + lvl.numYTiles() + ")");
        }
        String key = level + "/" + tx + "/" + ty;
        BufferedImage cached = tileCache.get(key);
        if (cached != null) {
            return cached;
        }
        byte[] raw = source.read(lvl.tileOffset(tx, ty), (int) lvl.tileByteCount(tx, ty));
        BufferedImage img = TileDecoder.decode(lvl, raw);
        tileCache.put(key, img);
        return img;
    }

    public void clearCache() {
        tileCache.clear();
    }

    @Override
    public void close() throws IOException {
        tileCache.clear();
        source.close();
    }
}
