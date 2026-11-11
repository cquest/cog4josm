package org.openstreetmap.josm.plugins.cog.reader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Roundtrip, fingerprint header, corrupt-file tolerance and directory pruning. */
class FileMetadataStoreTest {

    @TempDir
    Path dir;

    private static FileMetadataStore.Persisted block(long offset, int... values) {
        byte[] data = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            data[i] = (byte) values[i];
        }
        return new FileMetadataStore.Persisted(offset, data);
    }

    @Test
    void roundtripBlocksAndIdentity() throws IOException {
        FileMetadataStore s = new FileMetadataStore(dir, "cog");
        String url = "http://example.org/a.tif";
        assertNull(s.readIdentity(url), "absent URL has no identity");
        assertTrue(s.loadBlocks(url).isEmpty());
        s.writeAll(url, 1234, "\"abc\"", List.of(block(0, 1, 2, 3), block(8, 9, 8, 7, 6)));
        FileMetadataStore.Identity id = s.readIdentity(url);
        assertNotNull(id);
        assertEquals(1234, id.size());
        assertEquals("\"abc\"", id.etag());
        List<FileMetadataStore.Persisted> blocks = s.loadBlocks(url);
        assertEquals(2, blocks.size());
        assertEquals(0, blocks.get(0).offset());
        assertArrayEquals(new byte[]{1, 2, 3}, blocks.get(0).data());
        assertEquals(8, blocks.get(1).offset());
        assertArrayEquals(new byte[]{9, 8, 7, 6}, blocks.get(1).data());
        assertTrue(Files.exists(s.fileFor(url)), "file named <prefix>-<sha256>.cogmeta");
    }

    @Test
    void missingEtagRoundtripsAsNull() {
        FileMetadataStore s = new FileMetadataStore(dir, "cog");
        String url = "http://example.org/b.tif";
        s.writeAll(url, 10, null, List.of(block(0, 5)));
        FileMetadataStore.Identity id = s.readIdentity(url);
        assertNotNull(id);
        assertEquals(10, id.size());
        assertNull(id.etag());
    }

    @Test
    void rewriteReplacesAllBlocks() {
        FileMetadataStore s = new FileMetadataStore(dir, "cog");
        String url = "http://example.org/c.tif";
        s.writeAll(url, 10, "\"v1\"", List.of(block(0, 1, 1), block(4, 2, 2)));
        s.writeAll(url, 20, "\"v2\"", List.of(block(7, 3, 3)));
        assertEquals("\"v2\"", s.readIdentity(url).etag());
        List<FileMetadataStore.Persisted> blocks = s.loadBlocks(url);
        assertEquals(1, blocks.size());
        assertEquals(7, blocks.get(0).offset());
    }

    @Test
    void dropRemovesTheEntry() {
        FileMetadataStore s = new FileMetadataStore(dir, "cog");
        String url = "http://example.org/d.tif";
        s.writeAll(url, 10, null, List.of(block(0, 1)));
        assertNotNull(s.readIdentity(url));
        s.drop(url);
        assertNull(s.readIdentity(url));
        assertTrue(s.loadBlocks(url).isEmpty());
    }

    @Test
    void foreignOrCorruptFilesAreIgnored() throws IOException {
        FileMetadataStore s = new FileMetadataStore(dir, "cog");
        String url = "http://example.org/e.tif";
        Files.write(s.fileFor(url), "not a cogmeta file at all".getBytes(StandardCharsets.UTF_8));
        assertNull(s.readIdentity(url));
        assertTrue(s.loadBlocks(url).isEmpty());
        Files.write(s.fileFor(url), new byte[0]);
        assertNull(s.readIdentity(url));
        // a store from another plugin prefix is invisible:
        s.writeAll(url, 10, null, List.of(block(0, 1)));
        FileMetadataStore other = new FileMetadataStore(dir, "other");
        assertNull(other.readIdentity(url), "prefix discriminates stores");
    }

    @Test
    void truncatedRecordRefusedButIdentitySurvives() throws IOException {
        FileMetadataStore s = new FileMetadataStore(dir, "cog");
        String url = "http://example.org/f.tif";
        s.writeAll(url, 1234, "\"abc\"", List.of(block(0, 1, 2, 3)));
        Path f = s.fileFor(url);
        byte[] raw = Files.readAllBytes(f);
        Files.write(f, java.util.Arrays.copyOf(raw, raw.length - 2));
        // the last record runs past the end: blocks are refused (a later
        // revalidation will re-fetch and rewrite them), but the header and
        // its fingerprint are still readable
        assertTrue(s.loadBlocks(url).isEmpty());
        assertNotNull(s.readIdentity(url));
    }

    @Test
    void brokenRecordLengthRefusesBlocksButKeepsHeader() throws IOException {
        FileMetadataStore s = new FileMetadataStore(dir, "cog");
        String url = "http://example.org/g.tif";
        // crafted file: plausible header, one record claiming 256 MB of body
        ByteBuffer rec = ByteBuffer.allocate(12 + 100);
        rec.putLong(0).putInt(1 << 28).put(new byte[100]);
        byte[] recordBytes = rec.array();
        byte[] h = "cogmeta1\n1234\n\"abc\"\n".getBytes(StandardCharsets.UTF_8);
        byte[] raw = new byte[h.length + recordBytes.length];
        System.arraycopy(h, 0, raw, 0, h.length);
        System.arraycopy(recordBytes, 0, raw, h.length, recordBytes.length);
        Files.write(s.fileFor(url), raw);
        FileMetadataStore.Identity id = s.readIdentity(url);
        assertNotNull(id, "the header itself is consistent");
        assertEquals(1234, id.size(), "the size line is the remote file size");
        assertTrue(s.loadBlocks(url).isEmpty(), "absurd record length refuses every block");
    }

    @Test
    void oversizedBlocksAndFilesAreRefused() {
        FileMetadataStore s = new FileMetadataStore(dir, "cog");
        String url = "http://example.org/h.tif";
        // one block over the per-block cap is skipped, small ones survive
        byte[] big = new byte[FileMetadataStore.MAX_BLOCK_BYTES + 1];
        s.writeAll(url, 10, null, List.of(
                new FileMetadataStore.Persisted(0, big), block(100, 7)));
        List<FileMetadataStore.Persisted> blocks = s.loadBlocks(url);
        assertEquals(1, blocks.size(), "raster-sized block refused");
        // enough blocks to exceed the file cap: nothing is written at all
        String url2 = "http://example.org/i.tif";
        byte[] chunk = new byte[1 << 20];
        java.util.List<FileMetadataStore.Persisted> many = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            many.add(new FileMetadataStore.Persisted(i * chunk.length, chunk.clone()));
        }
        s.writeAll(url2, 100, null, many);
        assertNull(s.readIdentity(url2), "oversized store file refused upfront");
    }

    @Test
    void pruningDropsOldestBeyondTotalBudget() throws IOException {
        FileMetadataStore s = new FileMetadataStore(dir, "cog");
        // each store file: four sub-cap blocks, ~3.8 MiB total (near the
        // per-file cap, far over the 1 MiB per-block cap is not allowed)
        byte[] chunk = new byte[970_000];
        List<FileMetadataStore.Persisted> blocks = new java.util.ArrayList<>();
        for (int b = 0; b < 4; b++) {
            blocks.add(new FileMetadataStore.Persisted(b * chunk.length, chunk.clone()));
        }
        List<String> urls = new java.util.ArrayList<>();
        long base = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            String url = "http://example.org/p" + i + ".tif";
            urls.add(url);
            s.writeAll(url, 4L * chunk.length, null, blocks);
            // spread the ages by 10 s so pruning order is unambiguous
            Files.setLastModifiedTime(s.fileFor(url),
                    java.nio.file.attribute.FileTime.fromMillis(base + i * 10_000L));
        }
        long total = 0;
        int kept = 0;
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                total += Files.size(p);
                kept++;
            }
        }
        assertTrue(total <= FileMetadataStore.MAX_TOTAL_BYTES,
                "budget respected: " + total + " in " + kept + " files");
        assertTrue(kept < 5, "oldest files pruned: " + kept + " left");
        assertNull(s.readIdentity(urls.get(0)), "the oldest file was pruned");
        assertNotNull(s.readIdentity(urls.get(4)), "the file just written survives pruning");
    }
}
