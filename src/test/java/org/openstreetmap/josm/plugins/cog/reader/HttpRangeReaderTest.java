package org.openstreetmap.josm.plugins.cog.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end test: COG served by a local HTTP server handling the Range
 * header, read by HttpRangeReader then decomposed into tiles.
 */
class HttpRangeReaderTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger rangeHits = new AtomicInteger();
    private final AtomicInteger fullHits = new AtomicInteger();
    /** Raw Range headers received, in order. */
    private final List<String> rangeRequests = Collections.synchronizedList(new ArrayList<>());
    /** User-Agent headers received alongside the ranges above. */
    private final List<String> userAgents = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/image.tif", this::serve);
        server.createContext("/noranges.tif", this::serveNoRange);
        server.createContext("/huge200.tif", this::serveHuge200);
        server.createContext("/badrange.tif", this::serveBadRange);
        server.createContext("/fat206.tif", this::serveFat206);
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
            rangeRequests.add(range);
            userAgents.add(ex.getRequestHeaders().getFirst("User-Agent"));
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

    /** Server that ignores Range: always returns a full 200 (bad citizen). */
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

    /** Ignores Range and streams a chunked multi-MB 200 (memory bomb attempt). */
    private void serveHuge200(HttpExchange ex) throws IOException {
        try {
            ex.sendResponseHeaders(200, 0); // chunked
            try (OutputStream os = ex.getResponseBody()) {
                byte[] zeros = new byte[64 << 10];
                for (int i = 0; i < 128; i++) { // 8 MB
                    os.write(zeros);
                }
            } catch (IOException clientGone) {
                // the reader may abort early: expected
            }
        } finally {
            ex.close();
        }
    }

    /** 206 with absurd Content-Range digits (Long.parseLong overflow attempt). */
    private void serveBadRange(HttpExchange ex) throws IOException {
        try {
            ex.getResponseHeaders().add("Content-Range", "bytes 0-0/99999999999999999999999");
            ex.sendResponseHeaders(206, 1);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(0);
            } catch (IOException clientGone) {
                // expected
            }
        } finally {
            ex.close();
        }
    }

    /** 206 that announces and sends 4 MB where 1 byte was requested. */
    private void serveFat206(HttpExchange ex) throws IOException {
        try {
            ex.getResponseHeaders().add("Content-Range", "bytes 0-4194303/4194304");
            ex.sendResponseHeaders(206, 4 << 20);
            try (OutputStream os = ex.getResponseBody()) {
                byte[] zeros = new byte[64 << 10];
                for (int i = 0; i < 64; i++) {
                    os.write(zeros);
                }
            } catch (IOException clientGone) {
                // the reader aborts at the cap: expected
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
            assertEquals(List.of("bytes=0-0"), rangeRequests,
                    "fallback 1-byte probe: only when size() is asked before any read");
        }
    }

    @Test
    void speculativeHeadReadReplacesProbe() throws IOException {
        // no bytes=0-0 request and no size probe on the CogReader path: the
        // first request is the clamped 32 KiB head read, and it carries every
        // metadata byte of this small fixture (containment slicing afterwards)
        try (CogReader r = CogReader.open(url("/image.tif"))) {
            assertEquals(3857, r.info().crsCode);
            assertEquals(List.of("bytes=0-" + (CogReader.HEAD_READ_BYTES - 1)), rangeRequests,
                    "one clamped head read, no probe");
            assertEquals(1, rangeHits.get(), "parsing served from the head block");
        }
    }

    @Test
    void userAgentCommentCarriesTheRequestedRange() throws IOException {
        // debug aid: plain HTTP access logs (which usually ignore request
        // headers other than the Referer) then show which part of the file
        // each request asked for, in valid RFC 9110 comment form
        try (CogReader r = CogReader.open(url("/image.tif"))) {
            r.getTile(0, 2, 2);
        }
        assertFalse(rangeRequests.isEmpty());
        assertEquals(rangeRequests.size(), userAgents.size());
        for (int i = 0; i < rangeRequests.size(); i++) {
            String ua = userAgents.get(i);
            assertEquals("cog4josm/0.3.0 (JOSM COG plugin; https://josm.openstreetmap.de; range="
                    + rangeRequests.get(i).substring("bytes=".length()) + ")", ua);
            assertTrue(ua.chars().allMatch(c -> c >= 0x20 && c <= 0x7E), "header stays ASCII: " + ua);
        }
    }

    @Test
    void readsViaRangesOnly() throws IOException {
        try (CogReader r = CogReader.open(url("/image.tif"))) {
            BufferedImage img = r.getTile(0, 2, 2);
            assertEquals(16, img.getWidth());
            assertTrue(fullHits.get() == 0, "no full download");
            assertTrue(rangeHits.get() >= 2, "partial reads only: " + rangeHits.get());
            assertTrue(rangeHits.get() < 40, "avoids chatty requests: " + rangeHits.get());
        }
    }

    @Test
    void rejectsServerWithoutRangeSupport() {
        IOException e = assertThrows(IOException.class, () -> CogReader.open(url("/noranges.tif")));
        assertTrue(e instanceof RangeNotSupportedException, e.toString());
    }

    @Test
    void headReadDoesNotBufferIgnoredRangeBody() {
        // audit POC: a server ignoring Range used to stream its WHOLE body
        // into memory during the probe (now during the head read); the body
        // must be discarded instead
        IOException e = assertThrows(IOException.class, () -> CogReader.open(url("/huge200.tif")));
        assertTrue(e instanceof RangeNotSupportedException, e.toString());
    }

    @Test
    void absurdContentRangeDigitsRejected() {
        // 23-digit totals used to escape as NumberFormatException (unchecked)
        IOException e = assertThrows(IOException.class, () -> CogReader.open(url("/badrange.tif")));
        assertTrue(e.getMessage().contains("Content-Range"), e.getMessage());
    }

    @Test
    void oversizedBodyForRequestedRangeRejected() {
        // 4 MB of body for a 32 KiB head read: capped, surfaces as IOException
        IOException e = assertThrows(IOException.class, () -> CogReader.open(url("/fat206.tif")));
        assertTrue(e.getMessage().contains("large body"), e.getMessage());
    }

    @Test
    void rangeRequestIsValidPercentEncodedUrl() throws IOException {
        // dodgy but valid: the encoded URL must not break the reader
        String path = URLDecoder.decode("%2Fimage.tif", StandardCharsets.UTF_8);
        assertEquals("/image.tif", path);
        try (CogReader r = CogReader.open(url("/image.tif"))) {
            assertEquals(3857, r.info().crsCode);
        }
    }

    @Test
    void mergesMetadataReadsIntoFewRequests() throws IOException {
        try (CogReader r = CogReader.open(url("/image.tif"))) {
            r.getTile(0, 2, 2);
            assertTrue(fullHits.get() == 0, "no full download");
            // head read (metadata in one request) + one merged tile block:
            // well under the one-request-per-tag era (~30 requests)
            assertTrue(rangeHits.get() <= 4, "merged byte-ranges: " + rangeHits.get());
        }
    }

    @Test
    void secondOpenServesMetadataFromLruCache() throws IOException {
        try (CogReader r1 = CogReader.open(url("/image.tif"))) {
            assertEquals(3857, r1.info().crsCode);
        }
        int afterFirst = rangeHits.get();
        try (CogReader r2 = CogReader.open(url("/image.tif"))) {
            assertEquals(3857, r2.info().crsCode);
            assertEquals(1, rangeHits.get() - afterFirst,
                    "only the head read (size/ETag check) reaches the network");
            // raster content is never served from the metadata cache
            r2.getTile(0, 2, 2);
            assertEquals(afterFirst + 2, rangeHits.get());
        }
    }

    @Test
    void batchGetCoalescesAdjacentTiles() throws IOException {
        try (CogReader r = CogReader.open(url("/image.tif"))) {
            int before = rangeHits.get();
            java.util.Map<Long, BufferedImage> tiles = r.getTiles(0, 1, 2, 1, 2);
            assertEquals(4, tiles.size());
            int used = rangeHits.get() - before;
            assertTrue(used <= 2, "adjacent tiles fetched as one block: " + used);
            BufferedImage t = tiles.get(CogReader.tileKey(1, 2));
            assertNotNull(t);
            assertEquals(16, t.getWidth());
            int cached = rangeHits.get();
            assertSame(t, r.getTile(0, 1, 2), "decoded tile kept in the tile cache");
            assertEquals(cached, rangeHits.get());
        }
    }

    @Test
    void speculativeRingMakesNeighbourPansFree() throws IOException {
        // phase 2.7 E1: a fetched window is padded by one ring of tiles; a
        // move fully inside that ring costs nothing and does not chase it
        try (CogReader r = CogReader.open(url("/image.tif"))) {
            assertNotNull(r.getTile(0, 1, 1));
            int afterFirst = rangeHits.get();
            assertTrue(afterFirst >= 2, "head read + tile block(s)");
            assertNotNull(r.getTile(0, 2, 1));
            assertNotNull(r.getTile(0, 1, 2));
            assertNotNull(r.getTile(0, 2, 2), "inside the prefetched ring");
            assertEquals(afterFirst, rangeHits.get(), "neighbour tiles served from the ring LRU");
        }
    }

    @Test
    void prefetchBaseOverviewCostsOneMergedFetch() throws IOException {
        // phase 2.7 E2: the coarsest level, when small, is decoded in one go
        try (CogReader r = CogReader.open(url("/image.tif"))) {
            int afterOpen = rangeHits.get();
            int coarsest = r.info().levelCount() - 1;
            int n = r.prefetchBaseOverview(CogReader.OVERVIEW_PREFETCH_MAX_BYTES);
            assertEquals(4, n, "the 2x2-tile 32 px overview of the fixture");
            assertTrue(rangeHits.get() - afterOpen <= 2, "merged blocks: " + (rangeHits.get() - afterOpen));
            int afterPrefetch = rangeHits.get();
            assertNotNull(r.getTile(coarsest, 1, 1));
            assertEquals(afterPrefetch, rangeHits.get(), "overview served from the LRU");
            assertEquals(0, r.prefetchBaseOverview(1), "size cap respected");
        }
    }
}
