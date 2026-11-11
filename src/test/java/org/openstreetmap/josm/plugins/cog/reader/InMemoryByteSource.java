package org.openstreetmap.josm.plugins.cog.reader;

import java.io.IOException;
import java.util.Arrays;

/**
 * In-memory ByteSource for tests. Public: shared with tests of other
 * packages (layer/).
 */
public final class InMemoryByteSource implements ByteSource {

    private final byte[] data;

    public InMemoryByteSource(byte[] data) {
        this.data = data;
    }

    @Override
    public long size() {
        return data.length;
    }

    @Override
    public boolean supportsRanges() {
        return true;
    }

    @Override
    public byte[] read(long offset, int length) throws IOException {
        if (offset >= data.length) {
            return new byte[0];
        }
        int end = (int) Math.min(offset + length, data.length);
        return Arrays.copyOfRange(data, (int) offset, end);
    }

    @Override
    public void close() {
    }
}
