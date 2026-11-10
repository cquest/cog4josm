package org.openstreetmap.josm.plugins.cog.reader;

import java.io.IOException;
import java.util.Locale;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Spike CLI (phase 0 du plan) : affiche les metadonnees d'un COG distant lu
 * par byte-ranges, et eventuellement decode une tuile en PNG.
 *
 * Usage : CogDumpCli &lt;url&gt; [--tile NIVEAU TX TY [sortie.png]]
 */
public final class CogDumpCli {

    private CogDumpCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            System.out.println("Usage : CogDumpCli <url-cog> [--tile niveau tx ty [sortie.png]]");
            return;
        }
        String url = args[0];
        try (CogReader reader = CogReader.open(url)) {
            System.out.println(reader.info());
            for (int i = 1; i < args.length; i++) {
                if ("--tile".equals(args[i]) && i + 3 <= args.length - 1) {
                    int level = Integer.parseInt(args[++i]);
                    int tx = Integer.parseInt(args[++i]);
                    int ty = Integer.parseInt(args[++i]);
                    BufferedImage img = reader.getTile(level, tx, ty);
                    System.out.format(Locale.ROOT, "Tuile %d/%d,%d decodee : %dx%d%n", level, tx, ty,
                            img.getWidth(), img.getHeight());
                    if (i + 1 < args.length) {
                        File out = new File(args[++i]);
                        ImageIO.write(img, "png", out);
                        System.out.println("ECRIT : " + out.getAbsolutePath());
                    }
                }
            }
        } catch (RangeNotSupportedException e) {
            System.err.println("ERREUR : " + e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            System.err.println("ERREUR : " + e);
            System.exit(1);
        }
    }
}
