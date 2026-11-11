package org.openstreetmap.josm.plugins.cog.reader;

import java.io.IOException;

/**
 * ByteSource view used only while parsing headers: every read (header, IFD
 * blocks, external tag values) goes through the global {@link MetadataCache}.
 * Raster tile content must NOT pass here — {@link CogReader} reads tiles from
 * the underlying source directly. Deliberately does not close the wrapped
 * reader, whose lifetime belongs to the CogReader.
 */
final class MetadataCachingSource implements ByteSource {

    private final HttpRangeReader http;
    private final String url;

    MetadataCachingSource(HttpRangeReader http) {
        this.http = http;
        this.url = http.url();
    }

    @Override
    public long size() throws IOException {
        return http.size();
    }

    @Override
    public boolean supportsRanges() {
        return http.supportsRanges();
    }

    @Override
    public byte[] read(long offset, int length) throws IOException {
        byte[] hit = MetadataCache.global().lookup(url, offset, length);
        if (hit != null) {
            return hit;
        }
        byte[] data = http.read(offset, length);
        // stored under its actual byte count: a body clamped at the end of
        // the file must not be recorded as the longer range we asked for
        MetadataCache.global().store(url, offset, data);
        return data;
    }

    @Override
    public void close() {
        // the underlying reader stays open (tiles are fetched through it)
    }
}
