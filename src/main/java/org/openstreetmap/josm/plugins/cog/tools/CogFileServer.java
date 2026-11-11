package org.openstreetmap.josm.plugins.cog.tools;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Minimal static HTTP server supporting byte-range requests
 * (essential to serve a COG), strong ETags and If-None-Match/304 answers
 * (so the plugin's conditional metadata revalidation can be tried against
 * it), using only the JDK.
 *
 * Usage: java org.openstreetmap.josm.plugins.cog.tools.CogFileServer &lt;directory&gt; &lt;port&gt;
 * Meant for tests only (serving a sample COG to JOSM).
 */
public final class CogFileServer {

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d*)");

    private CogFileServer() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: CogFileServer <directory> <port>");
            System.exit(2);
        }
        Path root = Paths.get(args[0]).toAbsolutePath().normalize();
        int port = Integer.parseInt(args[1]);
        // loopback only: this test server exposes the whole directory tree
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> handle(exchange, root));
        server.start();
        System.out.println("CogFileServer listening on http://127.0.0.1:" + port + "/ root=" + root);
    }

    private static void handle(HttpExchange exchange, Path root) throws IOException {
        String path = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);
        Path file = root.resolve(path.substring(1)).normalize();
        try {
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            long size = Files.size(file);
            exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().add("Content-Type",
                    file.toString().endsWith(".tif") || file.toString().endsWith(".tiff")
                            ? "image/tiff; application=geotiff; profile=cloud-optimized" : "application/octet-stream");
            String etag = etagFor(file, size);
            exchange.getResponseHeaders().add("ETag", etag);
            String inm = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (inm != null && ("*".equals(inm) || inm.contains(etag))) {
                System.out.println(path + ": 304 not modified (If-None-Match)");
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            long start = 0;
            long end = size - 1;
            boolean partial = false;
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null) {
                Matcher m = RANGE.matcher(range);
                if (!m.find()) {
                    exchange.sendResponseHeaders(416, -1);
                    return;
                }
                start = Long.parseLong(m.group(1));
                if (!m.group(2).isEmpty()) {
                    end = Math.min(Long.parseLong(m.group(2)), size - 1);
                }
                if (start > end || start >= size) {
                    exchange.sendResponseHeaders(416, -1);
                    return;
                }
                partial = true;
            }
            long len = end - start + 1;
            System.out.println(path + ": " + (partial ? "206 range " + start + "-" + end : "200 full")
                    + " (" + len + " bytes)");
            if ("HEAD".equals(exchange.getRequestMethod())) {
                if (partial) {
                    exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + size);
                }
                exchange.sendResponseHeaders(partial ? 206 : 200, -1);
                return;
            }
            if (partial) {
                exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + size);
                exchange.sendResponseHeaders(206, len);
            } else {
                exchange.sendResponseHeaders(200, len);
            }
            try (OutputStream out = exchange.getResponseBody();
                 var in = Files.newInputStream(file)) {
                in.skipNBytes(start);
                byte[] buf = new byte[8192];
                long remaining = len;
                while (remaining > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) {
                        break;
                    }
                    out.write(buf, 0, n);
                    remaining -= n;
                }
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * Strong ETag cheap to compute for a test server: SHA-256 over the first
     * 8 KiB and the size — changes whenever the content changes near either
     * end of the file, which is where a COG's identity lives.
     */
    private static String etagFor(Path file, long size) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                md.update(in.readNBytes(8192));
            }
            md.update(Long.toString(size).getBytes(StandardCharsets.UTF_8));
            return '"' + HexFormat.of().formatHex(md.digest()).substring(0, 16) + '-' + size + '"';
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
    }
}
