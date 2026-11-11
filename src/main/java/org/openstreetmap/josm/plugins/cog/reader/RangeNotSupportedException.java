package org.openstreetmap.josm.plugins.cog.reader;

import java.io.IOException;

/** The server does not support HTTP {@code Range:} requests: the COG cannot be read partially. */
public class RangeNotSupportedException extends IOException {

    public RangeNotSupportedException(String url) {
        super("The server does not support Range requests for " + url
                + ": cannot read the COG by byte-ranges");
    }
}
