package org.openstreetmap.josm.plugins.cog.reader;

import java.io.Closeable;
import java.io.IOException;

/**
 * Random-access byte source. Abstraction used by the TIFF parser so that it can
 * read either a remote COG (byte-range access) or a local file (tests).
 */
public interface ByteSource extends Closeable {

    /** Total size in bytes. */
    long size() throws IOException;

    /** False if the source cannot answer partial reads. */
    boolean supportsRanges();

    /**
     * Reads {@code length} bytes starting at {@code offset} (may return fewer
     * bytes near the end of the file, never more).
     */
    byte[] read(long offset, int length) throws IOException;
}
