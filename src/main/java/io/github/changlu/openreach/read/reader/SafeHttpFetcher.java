package io.github.changlu.openreach.read.reader;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.security.UrlSafetyGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final HttpClient client;

    public SafeHttpFetcher(UrlSafetyGuard safetyGuard, WebCapabilityProperties properties) {
        this.safetyGuard = safetyGuard;
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getRead().getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public FetchedPage fetch(String rawUrl) {
        URI current = safetyGuard.validate(rawUrl);
        int redirects = 0;

        while (true) {
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofMillis(properties.getRead().getTimeoutMs()))
                    .header("User-Agent", properties.getRead().getUserAgent())
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                    .header("Accept-Language", properties.getRead().getAcceptLanguage())
                    .GET()
                    .build();
            long started = System.nanoTime();
            upstreamLog.info("[OPENREACH-UPSTREAM] http_start provider=read method=GET host={} path={} redirect={}",
                    current.getHost(), current.getPath(), redirects);
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                upstreamLog.info("[OPENREACH-UPSTREAM] http_response provider=read status={} host={} latencyMs={} contentType={} retryAfter={}",
                        status, current.getHost(), (System.nanoTime() - started) / 1_000_000L,
                        response.headers().firstValue("content-type").orElse("unknown"),
                        response.headers().firstValue("retry-after").orElse("none"));

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
                    continue;
                }

                if (status < 200 || status >= 300) {
                    response.body().close();
                    throw new UpstreamException("Upstream returned HTTP " + status);
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
                upstreamLog.warn("[OPENREACH-UPSTREAM] http_io_fail provider=read host={} latencyMs={} message={}",
                        current.getHost(), (System.nanoTime() - started) / 1_000_000L, ex.getMessage());
                throw new UpstreamException("Failed to read URL: " + ex.getMessage(), ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                upstreamLog.warn("[OPENREACH-UPSTREAM] http_interrupted provider=read host={} latencyMs={}",
                        current.getHost(), (System.nanoTime() - started) / 1_000_000L);
                throw new UpstreamException("Read interrupted", ex);
            }
        }
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

    public record FetchedPage(
            String finalUrl,
            String contentType,
            byte[] body,
            Map<String, List<String>> headers
    ) {}
}
