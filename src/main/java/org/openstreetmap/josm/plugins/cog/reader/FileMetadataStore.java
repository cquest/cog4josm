package org.openstreetmap.josm.plugins.cog.reader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * On-disk persistence of TIFF <em>metadata</em> blocks (phase 2.8): one file
 * per URL, kept in the JOSM cache directory so reopening a COG in a new JOSM
 * session costs one conditional request (304, zero body) instead of
 * re-fetching the header/IFD/GeoKey blocks. Raster tile bytes must NEVER go
 * through this store — same rule as {@link MetadataCache}.
 *
 * <p>File layout (text header, then binary records):</p>
 * <pre>
 * cogmeta1\n
 * &lt;file size&gt;\n
 * &quot;&lt;etag&gt;&quot;\n                       (empty line when the source has no ETag)
 * [8B offset][4B length][bytes] × N
 * </pre>
 *
 * <p>The header fingerprint (size, ETag) lets {@link MetadataCache} decide
 * whether the blocks still describe the current content. All IO is
 * best-effort: a missing directory, an unreadable or corrupt file simply
 * means "no persistence", never an error for the caller. Files are written
 * atomically (temp file + rename) and the directory is pruned by oldest
 * first beyond {@link #MAX_TOTAL_BYTES}.</p>
 *
 * <p>JDK-only, like the rest of the reader package.</p>
 */
public final class FileMetadataStore {

    static final String MAGIC = "cogmeta1";
    /** A single store file bigger than this is refused/ignored. */
    static final int MAX_FILE_BYTES = 4 << 20;
    /** One block bigger than this must be raster, not metadata: refused. */
    static final int MAX_BLOCK_BYTES = 1 << 20;
    /** Budget of the whole directory; beyond it, oldest files are pruned. */
    static final long MAX_TOTAL_BYTES = 16L << 20;

    /** The (size, ETag) fingerprint remembered for a URL, or null. */
    public record Identity(long size, String etag) {
    }

    /** One persisted metadata block. */
    public record Persisted(long offset, byte[] data) {
    }

    private final Path dir;
    private final String prefix;

    /**
     * @param dir        store directory (created on demand; e.g. a JOSM
     *                   base-directories cache directory)
     * @param namePrefix file-name discriminator (the plugin name), so one
     *                   shared cache directory can host several stores
     */
    public FileMetadataStore(Path dir, String namePrefix) {
        this.dir = dir.toAbsolutePath().normalize();
        this.prefix = namePrefix + "-";
    }

    public Path directory() {
        return dir;
    }

    /** Store file of a URL: {@code <prefix><sha256(url)>.cogmeta}. */
    Path fileFor(String url) {
        return dir.resolve(prefix + sha256Hex(url) + ".cogmeta");
    }

    /** The persisted fingerprint of a URL, or null (absent/corrupt/oversized). */
    public synchronized Identity readIdentity(String url) {
        try {
            Path f = fileFor(url);
            if (!Files.isRegularFile(f) || Files.size(f) > MAX_FILE_BYTES) {
                return null;
            }
            String[] header = readHeader(Files.readAllBytes(f));
            if (header == null) {
                return null;
            }
            long size;
            try {
                size = Long.parseLong(header[0]);
            } catch (NumberFormatException e) {
                return null;
            }
            String etag = header[1].isEmpty() ? null : header[1];
            return new Identity(size, etag);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The persisted blocks of a URL; empty when absent, corrupt or oversized. */
    public synchronized List<Persisted> loadBlocks(String url) {
        try {
            Path f = fileFor(url);
            if (!Files.isRegularFile(f) || Files.size(f) > MAX_FILE_BYTES) {
                return List.of();
            }
            byte[] raw = Files.readAllBytes(f);
            if (readHeader(raw) == null) {
                return List.of();
            }
            ByteBuffer bb = ByteBuffer.wrap(raw);
            int pos = skipHeader(bb);
            List<Persisted> out = new ArrayList<>();
            while (pos + 12 <= raw.length) {
                long offset = bb.getLong(pos);
                int length = bb.getInt(pos + 8);
                pos += 12;
                if (offset < 0 || length <= 0 || length > MAX_BLOCK_BYTES || pos + length > raw.length) {
                    return List.of(); // corrupt: refuse the whole file, never half of it
                }
                byte[] data = new byte[length];
                System.arraycopy(raw, pos, data, 0, length);
                pos += length;
                out.add(new Persisted(offset, data));
            }
            return out;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Replaces all persisted blocks of a URL (each a (offset, bytes) pair);
     * atomic temp-file + rename, silent when the caps are exceeded or the
     * disk misbehaves (persistence failures must never break a read).
     */
    public synchronized void writeAll(String url, long size, String etag, List<Persisted> blocks) {
        try {
            List<Persisted> kept = new ArrayList<>();
            int records = 0;
            for (Persisted p : blocks) {
                if (p == null || p.data() == null || p.data().length == 0
                        || p.data().length > MAX_BLOCK_BYTES || p.offset() < 0) {
                    continue;
                }
                kept.add(p);
                records += 12 + p.data().length;
            }
            String header = headerFor(size, etag);
            if (kept.isEmpty() || header.length() + records > MAX_FILE_BYTES) {
                return;
            }
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(header.length() + records);
            out.write(header.getBytes(StandardCharsets.UTF_8));
            ByteBuffer bb = ByteBuffer.allocate(12);
            for (Persisted p : kept) {
                bb.clear();
                bb.putLong(p.offset()).putInt(p.data().length).flip();
                out.write(bb.array());
                out.write(p.data());
            }
            byte[] raw = out.toByteArray();
            Path tmp = Files.createTempFile(dir, prefix, ".tmp");
            try {
                Files.write(tmp, raw);
                Path target = fileFor(url);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException slow) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                pruneSkipping(fileFor(url));
                // touch after pruning: the newest content must be the newest
                // file, whatever pruning did to its neighbours
                Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis()));
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException e) {
            // best-effort store: persistence failures are never reader failures
        }
    }

    /** Removes the persisted entry of a URL (fingerprint changed). */
    public synchronized void drop(String url) {
        try {
            Files.deleteIfExists(fileFor(url));
        } catch (IOException | RuntimeException e) {
            // best-effort
        }
    }

    /** Text header of a store file (magic + version line, size line, ETag line). */
    private static String headerFor(long size, String etag) {
        return MAGIC + "\n" + size + "\n" + (etag == null ? "" : etag) + "\n";
    }

    /**
     * Validates "cogmeta1\n&lt;remote size&gt;\n&lt;etag&gt;\n"; null when
     * malformed. The size line is the fingerprint of the <em>remote</em>
     * file, unrelated to the store file length: consistency of the records
     * is checked by their bounds during parsing, never here.
     */
    private static String[] readHeader(byte[] raw) {
        int lineEnd = -1;
        for (int i = 0; i < Math.min(raw.length, 16); i++) {
            if (raw[i] == '\n') {
                lineEnd = i;
                break;
            }
        }
        if (lineEnd < 0 || lineEnd > MAGIC.length()
                || !new String(raw, 0, lineEnd, StandardCharsets.UTF_8).equals(MAGIC)) {
            return null;
        }
        int p = lineEnd + 1;
        int next = indexOf(raw, '\n', p);
        if (next < 0) {
            return null;
        }
        String size = new String(raw, p, next - p, StandardCharsets.UTF_8);
        p = next + 1;
        next = indexOf(raw, '\n', p);
        if (next < 0) {
            return null;
        }
        String etag = new String(raw, p, next - p, StandardCharsets.UTF_8);
        if (size.isEmpty() || size.length() > 18 || !size.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return new String[]{size, etag};
    }

    /** Byte index just after the header. */
    private static int skipHeader(ByteBuffer bb) {
        int p = MAGIC.length(); // at the '\n' closing the magic line
        for (int line = 0; line < 2; line++) {
            p = indexOf(bb.array(), '\n', p + 1);
            if (p < 0) {
                return bb.array().length;
            }
        }
        return p + 1;
    }

    /** Prunes oldest store files beyond the total budget, keeping this one. */
    private void pruneSkipping(Path keep) {
        try (var stream = Files.list(dir)) {
            List<Path> files = stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(prefix) && n.endsWith(".cogmeta");
            }).toList();
            long total = 0;
            for (Path p : files) {
                total += Files.size(p);
            }
            if (total <= MAX_TOTAL_BYTES) {
                return;
            }
            List<Path> byAge = new ArrayList<>(files);
            byAge.sort(Comparator.comparingLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return 0;
                }
            }));
            for (Path p : byAge) {
                if (total <= MAX_TOTAL_BYTES) {
                    break;
                }
                if (p.equals(keep)) {
                    continue;
                }
                long len = Files.size(p);
                Files.delete(p);
                total -= len;
            }
        } catch (IOException | RuntimeException e) {
            // best-effort pruning
        }
    }

    private static int indexOf(byte[] raw, int b, int from) {
        for (int i = from; i < raw.length; i++) {
            if (raw[i] == (byte) b) {
                return i;
            }
        }
        return -1;
    }

    private static String sha256Hex(String url) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
    }
}
