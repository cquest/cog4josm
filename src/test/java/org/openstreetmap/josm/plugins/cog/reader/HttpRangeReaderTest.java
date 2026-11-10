package org.openstreetmap.josm.plugins.cog.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test de bout en bout : COG servi par un HTTP server local qui gere l'en-tete
 * Range, lu par HttpRangeReader puis decompose en tuiles.
 */
class HttpRangeReaderTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger rangeHits = new AtomicInteger();
    private final AtomicInteger fullHits = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/image.tif", this::serve);
        server.createContext("/noranges.tif", this::serveNoRange);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void serve(HttpExchange ex) throws IOException {
        try {
            byte[] tiff = cogBytes();
            String range = ex.getRequestHeaders().getFirst("Range");
            if (range == null) {
                fullHits.incrementAndGet();
                ex.getResponseHeaders().add("Accept-Ranges", "bytes");
                ex.sendResponseHeaders(200, tiff.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(tiff);
                }
                return;
            }
            if (!range.startsWith("bytes=")) {
                ex.sendResponseHeaders(400, -1);
                return;
            }
            String spec = range.substring("bytes=".length());
            String[] parts = spec.split("-");
            int from = Integer.parseInt(parts[0]);
            int to = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1]) : tiff.length - 1;
            to = Math.min(to, tiff.length - 1);
            int len = to - from + 1;
            rangeHits.incrementAndGet();
            ex.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + to + "/" + tiff.length);
            ex.sendResponseHeaders(206, len);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(tiff, from, len);
            }
        } finally {
            ex.close();
        }
    }

    /** Serveur qui ignore Range : renvoie toujours 200 plein (mauvais citizen). */
    private void serveNoRange(HttpExchange ex) throws IOException {
        try {
            byte[] tiff = cogBytes();
            ex.sendResponseHeaders(200, tiff.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(tiff);
            }
        } finally {
            ex.close();
        }
    }

    private static byte[] cogBytes() {
        try {
            return TestTiffWriter.write(List.of(
                    new TestTiffWriter.LevelSpec(64, 64, 8, 1, 8, false),
                    new TestTiffWriter.LevelSpec(32, 32, 8, 1, 8, false)),
                    3857, 10.0, 500000.0, 6250000.0);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @Test
    void probesSizeAndRanges() throws IOException {
        try (HttpRangeReader r = HttpRangeReader.open(url("/image.tif"), null)) {
            assertEquals(cogBytes().length, r.size());
            assertTrue(r.supportsRanges());
            assertTrue(rangeHits.get() >= 1);
        }
    }

    @Test
    void readsViaRangesOnly() throws IOException {
        try (CogReader r = CogReader.open(url("/image.tif"))) {
            BufferedImage img = r.getTile(0, 2, 2);
            assertEquals(16, img.getWidth());
            assertTrue(fullHits.get() == 0, "aucun telechargement complet");
            assertTrue(rangeHits.get() >= 4, "lectures partielles seulement : " + rangeHits.get());
            assertTrue(rangeHits.get() < 40, "evite le mitrage : " + rangeHits.get());
        }
    }

    @Test
    void rejectsServerWithoutRangeSupport() {
        IOException e = assertThrows(IOException.class, () -> CogReader.open(url("/noranges.tif")));
        assertTrue(e instanceof RangeNotSupportedException, e.toString());
    }

    @Test
    void rangeRequestIsValidPercentEncodedUrl() throws IOException {
        // fumeux mais valide : URL encodee ne doit pas casser le reader
        String path = URLDecoder.decode("%2Fimage.tif", StandardCharsets.UTF_8);
        assertEquals("/image.tif", path);
        try (CogReader r = CogReader.open(url("/image.tif"))) {
            assertEquals(3857, r.info().crsCode);
        }
    }
}
