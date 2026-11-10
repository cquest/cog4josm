package org.openstreetmap.josm.plugins.cog.reader;

import java.io.Closeable;
import java.io.IOException;

/**
 * Source d'octets a acces aleatoire. Abstraction utilisee par le parseur TIFF
 * pour pouvoir lire indifferemment un COG distant (acces par byte-ranges) ou
 * un fichier local (tests).
 */
public interface ByteSource extends Closeable {

    /** Taille totale en octets. */
    long size() throws IOException;

    /** Faux si la source ne sait pas repondre a des lectures partielles. */
    boolean supportsRanges();

    /**
     * Lit {@code length} octets a partir de {@code offset} (peut renvoyer moins
     * pres de la fin du fichier, jamais plus).
     */
    byte[] read(long offset, int length) throws IOException;
}
