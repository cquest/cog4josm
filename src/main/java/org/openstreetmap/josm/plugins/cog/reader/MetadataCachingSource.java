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
        long size = http.knownSize();
        if (size > CogReader.HEAD_READ_BYTES && offset >= size - CogReader.HEAD_READ_BYTES) {
            // header-tail COG (the GDAL driver's default layout): the IFDs and
            // the tile-offset tables sit at the very end of the file, outside
            // the speculative head read. One extra request fetches the whole
            // tail block, exactly like the head block, so the rest of the
            // parse is sliced from cache (and persisted for the next session)
            // instead of one request per IFD.
            long blockOffset = size - CogReader.HEAD_READ_BYTES;
            byte[] block = http.read(blockOffset, CogReader.HEAD_READ_BYTES);
            MetadataCache.global().store(url, blockOffset, block);
            int from = (int) (offset - blockOffset);
            int len = Math.min(length, block.length - from);
            byte[] slice = new byte[len];
            System.arraycopy(block, from, slice, 0, len);
            return slice;
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
