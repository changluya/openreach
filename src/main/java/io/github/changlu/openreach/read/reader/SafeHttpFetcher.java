package io.github.changlu.openreach.read.reader;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.common.UpstreamHttpException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.security.UrlSafetyGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class SafeHttpFetcher {
    private static final Logger upstreamLog = LoggerFactory.getLogger("OPENREACH.UPSTREAM");
    private final UrlSafetyGuard safetyGuard;
    private final WebCapabilityProperties properties;
    private final HttpSender sender;

    @Autowired
    public SafeHttpFetcher(UrlSafetyGuard safetyGuard, WebCapabilityProperties properties) {
        this.safetyGuard = safetyGuard;
        this.properties = properties;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, properties.getRead().effectiveConnectTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.sender = request -> client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    SafeHttpFetcher(UrlSafetyGuard safetyGuard, WebCapabilityProperties properties, HttpSender sender) {
        this.safetyGuard = safetyGuard;
        this.properties = properties;
        this.sender = sender;
    }

    public FetchedPage fetch(String rawUrl) {
        URI validated = safetyGuard.validate(rawUrl);
        URI current = normalizeKnownRedirector(validated);
        if (!current.equals(validated)) {
            current = safetyGuard.validate(current.toString());
            upstreamLog.info("[OPENREACH-UPSTREAM] normalize_redirector provider=read from={} to={}",
                    validated, current);
        }
        int redirects = 0;

        while (true) {
            boolean redirected = false;
            int maxAttempts = properties.getRead().effectiveMaxAttempts();
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                HttpRequest request = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofMillis(Math.max(1, properties.getRead().effectiveRequestTimeoutMs())))
                        .header("User-Agent", properties.getRead().getUserAgent())
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5")
                        .header("Accept-Language", properties.getRead().getAcceptLanguage())
                        // Browser-navigation compatibility headers. These improve ordinary public-page
                        // compatibility without carrying cookies, credentials or bypassing access controls.
                        .header("Cache-Control", "no-cache")
                        .header("Pragma", "no-cache")
                        .header("Upgrade-Insecure-Requests", "1")
                        .header("Sec-Fetch-Dest", "document")
                        .header("Sec-Fetch-Mode", "navigate")
                        .header("Sec-Fetch-Site", "none")
                        .header("Sec-Fetch-User", "?1")
                        .GET()
                        .build();
                long started = System.nanoTime();
                upstreamLog.info("[OPENREACH-UPSTREAM] http_start provider=read method=GET host={} path={} redirect={} attempt={}/{}",
                        current.getHost(), current.getPath(), redirects, attempt, maxAttempts);
                try {
                    HttpResponse<InputStream> response = sender.send(request);
                    int status = response.statusCode();
                    upstreamLog.info("[OPENREACH-UPSTREAM] http_response provider=read status={} host={} latencyMs={} contentType={} retryAfter={} attempt={}/{}",
                            status, current.getHost(), (System.nanoTime() - started) / 1_000_000L,
                            response.headers().firstValue("content-type").orElse("unknown"),
                            response.headers().firstValue("retry-after").orElse("none"), attempt, maxAttempts);

                    if (status >= 300 && status < 400) {
                        response.body().close();
                        if (++redirects > properties.getRead().getMaxRedirects()) {
                            throw new UpstreamException("Too many redirects");
                        }
                        Optional<String> location = response.headers().firstValue("location");
                        if (location.isEmpty()) throw new UpstreamException("Redirect response missing Location header");
                        URI next = safetyGuard.validate(current.resolve(location.get()).toString());
                        upstreamLog.info("[OPENREACH-UPSTREAM] redirect provider=read fromHost={} toHost={} status={} redirect={}",
                                current.getHost(), next.getHost(), status, redirects);
                        current = next;
                        redirected = true;
                        break;
                    }

                    if (status < 200 || status >= 300) {
                        response.body().close();
                        boolean retryableStatus = isRetryableHttpStatus(status);
                        if (retryableStatus && attempt < maxAttempts) {
                            upstreamLog.warn("[OPENREACH-UPSTREAM] http_status_retry provider=read status={} host={} attempt={}/{}",
                                    status, current.getHost(), attempt, maxAttempts);
                            sleepBackoff(properties.getRead().effectiveRetryBackoffMs());
                            continue;
                        }
                        throw new UpstreamHttpException(status, retryableStatus);
                    }

                    String contentType = response.headers().firstValue("content-type").orElse("application/octet-stream");
                    if (!isReadableContentType(contentType)) {
                        response.body().close();
                        throw new BadRequestException("V1 read only supports HTML/XHTML/plain text, got: " + contentType);
                    }

                    int maxBytes = properties.getRead().getMaxBytes();
                    byte[] body = readLimited(response.body(), maxBytes);
                    return new FetchedPage(current.toString(), contentType, body, response.headers().map());
                } catch (IOException ex) {
                    long latencyMs = (System.nanoTime() - started) / 1_000_000L;
                    if (attempt < maxAttempts) {
                        upstreamLog.warn("[OPENREACH-UPSTREAM] http_retry provider=read host={} latencyMs={} attempt={}/{} reason={} message={}",
                                current.getHost(), latencyMs, attempt, maxAttempts,
                                ex.getClass().getSimpleName(), compact(ex.getMessage()));
                        sleepBackoff(properties.getRead().effectiveRetryBackoffMs());
                        continue;
                    }
                    upstreamLog.warn("[OPENREACH-UPSTREAM] http_io_fail provider=read host={} latencyMs={} attempts={} reason={} message={}",
                            current.getHost(), latencyMs, maxAttempts, ex.getClass().getSimpleName(), compact(ex.getMessage()));
                    throw new UpstreamException("Failed to read URL after " + maxAttempts + " attempt(s): " + compact(ex.getMessage()), ex);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    upstreamLog.warn("[OPENREACH-UPSTREAM] http_interrupted provider=read host={} latencyMs={} attempt={}/{}",
                            current.getHost(), (System.nanoTime() - started) / 1_000_000L, attempt, maxAttempts);
                    throw new UpstreamException("Read interrupted", ex);
                }
            }
            if (redirected) continue;

            // The loop can only fall through when maxAttempts was misconfigured to <= 0,
            // but effectiveMaxAttempts() clamps it to at least one. Keep an explicit guard.
            throw new UpstreamException("Failed to read URL: no HTTP attempt was executed");
        }
    }


    URI normalizeKnownRedirector(URI uri) {
        if (uri == null || uri.getHost() == null) return uri;
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean baiduLink = (host.equals("baidu.com") || host.endsWith(".baidu.com"))
                && "/link".equals(uri.getPath());
        if (!baiduLink || !"http".equalsIgnoreCase(uri.getScheme())) return uri;
        try {
            return new URI("https", null, uri.getHost(), uri.getPort() == 80 ? -1 : uri.getPort(),
                    uri.getPath(), uri.getQuery(), uri.getFragment());
        } catch (Exception ex) {
            return uri;
        }
    }

    private void sleepBackoff(int millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("Read retry interrupted", ex);
        }
    }

    private String compact(String message) {
        if (message == null || message.isBlank()) return "unknown I/O error";
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private boolean isRetryableHttpStatus(int status) {
        // Read is a GET-only primitive, so one bounded retry is safe for transient gateway/origin failures.
        // Deliberately exclude 401/403/412/429: those represent credentials/access conditions/rate limits
        // and retrying the same request would only amplify upstream pressure.
        return status == 408
                || status == 425
                || status == 500
                || status == 502
                || status == 503
                || status == 504
                || (status >= 520 && status <= 524);
    }

    private boolean isReadableContentType(String contentType) {
        String lower = contentType.toLowerCase();
        return lower.contains("text/html")
                || lower.contains("application/xhtml+xml")
                || lower.contains("text/plain");
    }

    private byte[] readLimited(InputStream in, int maxBytes) throws IOException {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new BadRequestException("Response body exceeds configured maxBytes=" + maxBytes);
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    @FunctionalInterface
    interface HttpSender {
        HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;
    }

    public record FetchedPage(
            String finalUrl,
            String contentType,
            byte[] body,
            Map<String, List<String>> headers
    ) {}
}
