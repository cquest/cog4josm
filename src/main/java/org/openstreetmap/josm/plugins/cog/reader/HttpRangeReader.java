package org.openstreetmap.josm.plugins.cog.reader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture HTTP partielle d'une ressource distante via l'en-tete {@code Range: bytes=...}.
 * Sonde une seule fois le support des ranges (GET {@code bytes=0-0}), met en cache la
 * taille et l'ETag, retry sur les erreurs 5xx.
 */
public final class HttpRangeReader implements ByteSource {

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)");
    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient client;
    private final URI uri;
    private final Map<String, String> headers;

    private long size = -1;
    private boolean probed;
    private boolean rangesSupported;
    private String etag;

    public HttpRangeReader(String url, Map<String, String> extraHeaders) {
        this.uri = URI.create(url);
        this.headers = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** Construit le lecteur et verifie immediatement la taille + le support des ranges. */
    public static HttpRangeReader open(String url, Map<String, String> extraHeaders) throws IOException {
        HttpRangeReader reader = new HttpRangeReader(url, extraHeaders);
        reader.probe();
        return reader;
    }

    public String url() {
        return uri.toString();
    }

    public String etag() {
        return etag;
    }

    @Override
    public long size() throws IOException {
        probe();
        return size;
    }

    @Override
    public boolean supportsRanges() {
        try {
            probe();
        } catch (IOException e) {
            return false;
        }
        return rangesSupported;
    }

    private synchronized void probe() throws IOException {
        if (probed) {
            return;
        }
        HttpResponse<byte[]> resp = send(rangeRequest("0-0"), HttpResponse.BodyHandlers.ofByteArray());
        int status = resp.statusCode();
        if (status == 206) {
            rangesSupported = true;
            String cr = header(resp, "Content-Range");
            Matcher m = cr == null ? null : CONTENT_RANGE.matcher(cr);
            if (m == null || !m.matches()) {
                throw new IOException("Reponse 206 sans Content-Range valide pour " + uri);
            }
            String total = m.group(3);
            if ("*".equals(total)) {
                throw new IOException("Content-Range sans taille totale pour " + uri);
            }
            size = Long.parseLong(total);
        } else if (status == 200) {
            rangesSupported = false;
            size = resp.body().length;
        } else if (status >= 400) {
            throw new IOException("HTTP " + status + " en telechargeant " + uri);
        }
        etag = header(resp, "ETag");
        probed = true;
    }

    @Override
    public byte[] read(long offset, int length) throws IOException {
        probe();
        if (!rangesSupported) {
            throw new RangeNotSupportedException(uri.toString());
        }
        if (offset >= size) {
            return new byte[0];
        }
        int count = (int) Math.min(length, size - offset);
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return fetchRange(offset, count);
            } catch (ShortReadException e) {
                last = e;
                sleepBeforeRetry(attempt);
            } catch (IOException e) {
                last = e;
                if (attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                sleepBeforeRetry(attempt);
            }
        }
        throw last;
    }

    private byte[] fetchRange(long offset, int count) throws IOException {
        HttpResponse<byte[]> resp = send(rangeRequest(offset + "-" + (offset + count - 1)),
                HttpResponse.BodyHandlers.ofByteArray());
        int status = resp.statusCode();
        if (status == 416) {
            throw new IOException("Range 416 sur " + uri + " (taille=" + size + ", demande=" + offset + ")");
        }
        if (status >= 500) {
            throw new IOException("HTTP " + status + " (retryable) sur " + uri);
        }
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " sur " + uri);
        }
        byte[] body = resp.body();
        if (status == 206) {
            String cr = header(resp, "Content-Range");
            Matcher m = cr == null ? null : CONTENT_RANGE.matcher(cr);
            if (m != null && m.matches() && Long.parseLong(m.group(1)) != offset) {
                throw new IOException("Content-Range incoherent pour " + uri + " : " + cr);
            }
        }
        if (body.length < count) {
            throw new ShortReadException("Range incomplet (" + body.length + "/" + count + ") sur " + uri);
        }
        if (body.length > count) {
            byte[] trimmed = new byte[count];
            System.arraycopy(body, 0, trimmed, 0, count);
            return trimmed;
        }
        return body;
    }

    private HttpRequest rangeRequest(String range) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Range", "bytes=" + range)
                .header("User-Agent", "josm-cog/0.1 (JOSM COG plugin; https://josm.openstreetmap.de)")
                .GET();
        headers.forEach((k, v) -> {
            if (!"range".equalsIgnoreCase(k)) {
                b.header(k, v);
            }
        });
        return b.build();
    }

    private HttpResponse<byte[]> send(HttpRequest req, HttpResponse.BodyHandler<byte[]> handler) throws IOException {
        try {
            return client.send(req, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Requis interrompue : " + uri, e);
        }
    }

    private static String header(HttpResponse<?> resp, String name) {
        return resp.headers().firstValue(name).orElse(null);
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(200L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ShortReadException extends IOException {
        ShortReadException(String message) {
            super(message);
        }
    }

    @Override
    public void close() {
        // HttpClient se recycle tout seul ; rien a liberer.
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "HttpRangeReader[%s, size=%d, ranges=%s]", uri, size, rangesSupported);
    }
}
