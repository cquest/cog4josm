package org.openstreetmap.josm.plugins.cog.reader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Partial HTTP read of a remote resource through the {@code Range: bytes=...} header.
 * Probes range support only once (GET {@code bytes=0-0}), caches the
 * size and the ETag, retries on 5xx errors.
 */
public final class HttpRangeReader implements ByteSource {

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d{1,18})-(\\d{1,18})/(\\d{1,18}|\\*)");
    private static final int MAX_ATTEMPTS = 3;
    /** Largest 206 body tolerated for the 1-byte probe before the server is refused. */
    private static final int PROBE_BODY_CAP = 64;
    /**
     * Cap used when the body is unwanted (error status or 200 on a Range
     * request): non-zero so the subscription is cancelled on the first chunk
     * instead of draining the whole body, but too small to buffer anything.
     */
    private static final int DROP_BODY_CAP = 16;

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

    /** Builds the reader and immediately checks the size + range support. */
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
        // the body is NEVER kept unbounded here: a server that ignores the
        // Range header (status 200) would otherwise stream the whole file
        // into RAM, so every body is capped (CappedBody) and the subscription
        // is cancelled as soon as the cap is reached
        HttpResponse<byte[]> resp = send(rangeRequest("0-0"),
                ri -> new CappedBody(ri.statusCode() == 206 ? PROBE_BODY_CAP + 1L : DROP_BODY_CAP));
        int status = resp.statusCode();
        if (status == 206) {
            byte[] body = resp.body();
            if (body != null && body.length > PROBE_BODY_CAP) {
                // a compliant 206 to bytes=0-0 is 1 byte: a bigger body is
                // a hostile or broken server (CappedBody bounded the copy anyway)
                throw new IOException("206 probe of " + uri + " with an unexpectedly large body");
            }
            rangesSupported = true;
            String cr = header(resp, "Content-Range");
            Matcher m = cr == null ? null : CONTENT_RANGE.matcher(cr);
            if (m == null || !m.matches()) {
                throw new IOException("206 response with no valid Content-Range for " + uri);
            }
            String total = m.group(3);
            if ("*".equals(total)) {
                throw new IOException("Content-Range without a total size for " + uri);
            }
            size = Long.parseLong(total);
        } else if (status == 200) {
            rangesSupported = false;
            String cl = header(resp, "Content-Length");
            long declared = -1;
            if (cl != null) {
                try {
                    declared = Long.parseLong(cl);
                } catch (NumberFormatException ignored) {
                    // keep -1: unknown size
                }
            }
            size = declared;
        } else if (status >= 400) {
            throw new IOException("HTTP " + status + " while downloading " + uri);
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
        // the 206 response body is capped at count+1 bytes (CappedBody): a
        // hostile or broken server can never make us buffer more than the
        // range we asked for; other statuses read almost nothing
        // (DROP_BODY_CAP) before the subscription is cancelled
        HttpResponse<byte[]> resp = send(rangeRequest(offset + "-" + (offset + count - 1)),
                ri -> new CappedBody(ri.statusCode() == 206 ? (long) count + 1 : DROP_BODY_CAP));
        int status = resp.statusCode();
        if (status == 416) {
            throw new IOException("Range 416 on " + uri + " (size=" + size + ", request=" + offset + ")");
        }
        if (status >= 500) {
            throw new IOException("HTTP " + status + " (retryable) on " + uri);
        }
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " on " + uri);
        }
        if (status != 206) {
            // 200 on a Range request: the whole file would come, never accepted
            throw new RangeNotSupportedException(uri.toString());
        }
        String cr = header(resp, "Content-Range");
        Matcher m = cr == null ? null : CONTENT_RANGE.matcher(cr);
        if (m != null && m.matches() && Long.parseLong(m.group(1)) != offset) {
            throw new IOException("Inconsistent Content-Range for " + uri + ": " + cr);
        }
        byte[] body = resp.body() == null ? new byte[0] : resp.body();
        if (body.length > count) {
            // the copy was capped at count+1: the server sent more than requested
            throw new IOException("Server sent more than the requested range ("
                    + count + " bytes) on " + uri);
        }
        if (body.length < count) {
            throw new ShortReadException("Incomplete range (" + body.length + "/" + count + ") on " + uri);
        }
        return body;
    }

    private HttpRequest rangeRequest(String range) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Range", "bytes=" + range)
                .header("User-Agent", "cog4josm/0.2.0 (JOSM COG plugin; https://josm.openstreetmap.de)")
                .GET();
        headers.forEach((k, v) -> {
            if (!"range".equalsIgnoreCase(k)) {
                b.header(k, v);
            }
        });
        return b.build();
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return client.send(req, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted request: " + uri, e);
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

    /**
     * Body subscriber that copies at most {@code keep} bytes, then cancels the
     * subscription: a hostile or broken server can never make this client
     * buffer more than {@code keep} bytes. A result of exactly {@code keep}
     * bytes means the real body was at least that big (JDK 17 has no
     * {@code BodySubscribers.limit}, added only in JDK 21).
     */
    private static final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final long keep;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> done = new CompletableFuture<>();
        private volatile Flow.Subscription subs;

        CappedBody(long keep) {
            this.keep = keep;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return done;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subs = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (done.isDone()) {
                return;
            }
            boolean overflow = false;
            for (ByteBuffer b : buffers) {
                if (out.size() + b.remaining() > keep) {
                    copy(b, (int) Math.max(0, keep - out.size()));
                    overflow = true;
                    break;
                }
                copy(b, b.remaining());
            }
            if (overflow) {
                subs.cancel();
                done.complete(out.toByteArray());
            }
        }

        @Override
        public void onError(Throwable throwable) {
            done.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            done.complete(out.toByteArray());
        }

        private void copy(ByteBuffer b, int n) {
            if (n <= 0) {
                return;
            }
            byte[] chunk = new byte[n];
            b.get(chunk);
            out.write(chunk, 0, n);
        }
    }

    @Override
    public void close() {
        // the HttpClient recycles itself; nothing to release.
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "HttpRangeReader[%s, size=%d, ranges=%s]", uri, size, rangesSupported);
    }
}
