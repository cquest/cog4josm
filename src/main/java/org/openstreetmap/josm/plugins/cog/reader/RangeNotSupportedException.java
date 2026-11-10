package org.openstreetmap.josm.plugins.cog.reader;

import java.io.IOException;

/** Le serveur ne supporte pas les requis HTTP {@code Range:} : le COG ne peut pas etre lu partiellement. */
public class RangeNotSupportedException extends IOException {

    public RangeNotSupportedException(String url) {
        super("Le serveur ne supporte pas les requetes Range pour " + url
                + " : impossible de lire le COG par byte-ranges");
    }
}
