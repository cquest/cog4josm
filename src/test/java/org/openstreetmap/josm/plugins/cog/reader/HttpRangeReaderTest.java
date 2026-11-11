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
import java.nio.file.Files;
import java.nio.file.Path;
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
    /** Requests received with If-None-Match, and matching 304 answers. */
    private final AtomicInteger conditionalHits = new AtomicInteger();
    private final AtomicInteger notModified = new AtomicInteger();
    /** Body bytes actually sent on 206 answers. */
    private final java.util.concurrent.atomic.AtomicLong rangeBytes = new java.util.concurrent.atomic.AtomicLong();
    /** Raw Range headers received, in order. */
    private final List<String> rangeRequests = Collections.synchronizedList(new ArrayList<>());
    /** User-Agent headers received alongside the ranges above. */
    private final List<String> userAgents = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/image.tif", this::serve);
        server.createContext("/tail.tif", this::serveTail);
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
        serveBytes(ex, cogBytes(), testEtag());
    }

    /** Header-tail layout COG: IFDs and tables at the very end of the file. */
    private void serveTail(HttpExchange ex) throws IOException {
        serveBytes(ex, tailBytes(), tailEtag());
    }

    private void serveBytes(HttpExchange ex, byte[] tiff, String etag) throws IOException {
        try {
            String range = ex.getRequestHeaders().getFirst("Range");
            if (range != null && range.startsWith("bytes=")) {
                rangeRequests.add(range);
                userAgents.add(ex.getRequestHeaders().getFirst("User-Agent"));
                rangeHits.incrementAndGet(); // a 304 is still a request
            }
            ex.getResponseHeaders().add("ETag", etag);
            String inm = ex.getRequestHeaders().getFirst("If-None-Match");
            if (inm != null && inm.contains(etag)) {
                conditionalHits.incrementAndGet();
                notModified.incrementAndGet();
                ex.sendResponseHeaders(304, -1);
                return;
            }
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
            rangeBytes.addAndGet(len);
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

    /** Strong ETag of the fixture, as a real COG host would serve one. */
    private static String testEtag;
    private static String tailEtag;

    private static String testEtag() {
        if (testEtag == null) {
            testEtag = etagOf(cogBytes());
        }
        return testEtag;
    }

    private static String tailEtag() {
        if (tailEtag == null) {
            tailEtag = etagOf(tailBytes());
        }
        return tailEtag;
    }

    private static String etagOf(byte[] t) {
        try {
            return '"' + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(t))
                    .substring(0, 16) + '"';
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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

    /**
     * Header-tail fixture: three uncompressed levels (~212 KiB of tiles at
     * the head, IFDs and offset tables in the last ~3 KiB), far too big for
     * the 32 KiB head read to reach the metadata.
     */
    private static byte[] tailBytes() {
        try {
            return TestTiffWriter.writeTail(List.of(
                    new TestTiffWriter.LevelSpec(256, 256, 1, 1, 8, false),
                    new TestTiffWriter.LevelSpec(64, 64, 1, 1, 8, false),
                    new TestTiffWriter.LevelSpec(16, 16, 1, 1, 8, false)),
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
            assertEquals("cog4josm/0.4.0 (JOSM COG plugin; https://josm.openstreetmap.de; range="
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

    @Test
    void reopenedLayerRevalidatesWithZeroBodyBytes() throws IOException {
        // phase 2.8: metadata persists on disk; reopening a COG in a "new
        // session" is one conditional request — a 304 with zero body bytes
        Path dir = Files.createTempDirectory("cogmeta-test");
        FileMetadataStore store = new FileMetadataStore(dir, "test");
        MetadataCache cache = MetadataCache.global();
        String url = url("/image.tif");
        try {
            cache.attachStore(store);
            try (CogReader r = CogReader.open(url)) {
                assertEquals(3857, r.info().crsCode);
                assertEquals(1, rangeHits.get(), "first open: one plain head read");
                assertEquals(0, conditionalHits.get(), "nothing known yet: no If-None-Match");
                assertTrue(rangeBytes.get() > 0, "the head block came over the wire");
                FileMetadataStore.Identity id = store.readIdentity(url);
                assertNotNull(id, "fingerprint persisted for the next session");
                assertEquals(cogBytes().length, id.size());
                assertEquals(testEtag(), id.etag());
                assertFalse(store.loadBlocks(url).isEmpty(), "head block persisted too");
            }
            cache.clearAll(); // simulate a JOSM restart
            int reqBefore = rangeHits.get();
            long bytesBefore = rangeBytes.get();
            try (CogReader r = CogReader.open(url)) {
                assertEquals(3857, r.info().crsCode, "metadata comes from the store");
                assertEquals(1, rangeHits.get() - reqBefore, "one conditional request");
                assertEquals(1, conditionalHits.get(), "If-None-Match was sent");
                assertEquals(1, notModified.get(), "the server answered 304");
                assertEquals(0, rangeBytes.get() - bytesBefore, "304: not one body byte");
                BufferedImage t = r.getTile(0, 2, 2);
                assertNotNull(t);
                assertTrue(rangeBytes.get() - bytesBefore > 0, "raster bytes still fetched");
            }
            // tamper the persisted fingerprint: the file pretends to have
            // changed, the 304 never arrives, stale blocks are dropped and
            // the store is rewritten with the real one
            store.writeAll(url, 12, "\"tampered\"",
                    List.of(new FileMetadataStore.Persisted(0, new byte[8])));
            cache.clearAll();
            try (CogReader r = CogReader.open(url)) {
                assertEquals(3857, r.info().crsCode, "re-read after fingerprint mismatch");
                assertTrue(notModified.get() <= 1, "the tampered ETag got no 304");
            }
            FileMetadataStore.Identity fresh = store.readIdentity(url);
            assertNotNull(fresh);
            assertEquals(cogBytes().length, fresh.size(), "store rewritten with the real file");
            assertEquals(testEtag(), fresh.etag());
        } finally {
            MetadataCache.global().attachStore(null);
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    @Test
    void headerTailCogOpensWithTwoRequests() throws IOException {
        // GDAL header-tail layout: the IFDs sit at the very end, outside the
        // 32 KiB head read; one speculative tail block (same size as the
        // head) brings every metadata byte, so no per-IFD request follows
        MetadataCache.global().clearAll();
        byte[] tiff = tailBytes();
        assertTrue(tiff.length > CogReader.HEAD_READ_BYTES, "fixture must not fit the head read");
        long tailStart = tiff.length - CogReader.HEAD_READ_BYTES;
        try (CogReader r = CogReader.open(url("/tail.tif"))) {
            assertEquals(3857, r.info().crsCode);
            assertEquals(3, r.info().levelCount());
            assertEquals(List.of("bytes=0-" + (CogReader.HEAD_READ_BYTES - 1),
                    "bytes=" + tailStart + "-" + (tiff.length - 1)), rangeRequests,
                    "head read + one speculative tail block, nothing else");
            BufferedImage img = r.getTile(0, 8, 8);
            assertEquals(16, img.getWidth(), "tile offsets parsed from the tail block");
            // the 3x3 fetch window (tile + speculative ring) spans three
            // rows whose gaps exceed FETCH_MAX_GAP: at most one request/row
            assertTrue(rangeHits.get() <= 2 + 3, "raster fetch stays merged: " + rangeHits.get());
        }
    }

    @Test
    void headerTailReopensWithOneConditionalRequest() throws IOException {
        // the tail block is persisted (mirrorToStore runs after the parse),
        // so a "new session" revalidates with one 304 and parses the whole
        // header-tail IFD chain from the store: zero body bytes
        Path dir = Files.createTempDirectory("cogmeta-tail");
        FileMetadataStore store = new FileMetadataStore(dir, "test");
        MetadataCache cache = MetadataCache.global();
        String url = url("/tail.tif");
        try {
            cache.clearAll();
            cache.attachStore(store);
            try (CogReader r = CogReader.open(url)) {
                assertEquals(3857, r.info().crsCode);
                assertEquals(2, rangeHits.get(), "first open: head read + tail block");
                long size = tailBytes().length;
                assertTrue(store.loadBlocks(url).stream()
                                .anyMatch(p -> p.offset() + p.data().length == size),
                        "tail block reached the store: one persisted block ends at EOF");
            }
            cache.clearAll(); // simulate a JOSM restart
            int reqBefore = rangeHits.get();
            long bytesBefore = rangeBytes.get();
            try (CogReader r = CogReader.open(url)) {
                assertEquals(3857, r.info().crsCode, "IFD chain parsed from the persisted tail block");
                assertEquals(1, rangeHits.get() - reqBefore, "one conditional request");
                assertEquals(1, conditionalHits.get(), "If-None-Match was sent");
                assertEquals(1, notModified.get(), "the server answered 304");
                assertEquals(0, rangeBytes.get() - bytesBefore, "zero body bytes on reopen");
            }
        } finally {
            MetadataCache.global().attachStore(null);
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }
}
