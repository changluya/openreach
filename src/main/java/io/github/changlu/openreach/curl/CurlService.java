package io.github.changlu.openreach.curl;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.common.BoundedBodyReader;
import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.curl.dto.CurlRequest;
import io.github.changlu.openreach.curl.dto.CurlResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CurlService {
    private static final Logger upstreamLog = LoggerFactory.getLogger("OPENREACH.UPSTREAM");
    private static final Pattern CHARSET_PATTERN = Pattern.compile("charset\\s*=\\s*[\\\"']?([^;\\s\\\"']+)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> FORBIDDEN_REQUEST_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "cookie2", "host",
            "connection", "proxy-connection", "upgrade", "transfer-encoding", "content-length",
            "forwarded", "via", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto",
            "x-real-ip", "x-original-url", "x-rewrite-url",
            "x-api-key", "api-key", "x-auth-token", "x-access-token", "private-token"
    );
    private static final Set<String> HIDDEN_RESPONSE_HEADERS = Set.of("set-cookie", "set-cookie2");

    private final CurlTargetGuard targetGuard;
    private final WebCapabilityProperties properties;
    private final HttpSender sender;

    @Autowired
    public CurlService(CurlTargetGuard targetGuard, WebCapabilityProperties properties) {
        this.targetGuard = targetGuard;
        this.properties = properties;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, properties.getCurl().getConnectTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.sender = request -> client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    CurlService(CurlTargetGuard targetGuard, WebCapabilityProperties properties, HttpSender sender) {
        this.targetGuard = targetGuard;
        this.properties = properties;
        this.sender = sender;
    }

    public CurlResponse execute(CurlRequest request, SelfTargetContext self) {
        long started = System.nanoTime();
        String method = normalizeMethod(request.method());
        Map<String, String> requestHeaders = validateHeaders(request.headers());
        URI current = targetGuard.validate(request.url(), self);
        int redirects = 0;

        upstreamLog.info("[OPENREACH-CURL] curl_start method={} host={} path={}", method, current.getHost(), safePath(current));
        try {
            while (true) {
                HttpRequest outbound = buildRequest(current, method, requestHeaders);
                long httpStarted = System.nanoTime();
                HttpResponse<InputStream> response = sender.send(outbound);
                int status = response.statusCode();
                upstreamLog.info("[OPENREACH-UPSTREAM] http_response provider=curl method={} status={} host={} latencyMs={} contentType={}",
                        method, status, current.getHost(), (System.nanoTime() - httpStarted) / 1_000_000L,
                        response.headers().firstValue("content-type").orElse("unknown"));

                if (status >= 300 && status < 400 && properties.getCurl().isFollowRedirects()) {
                    Optional<String> location = response.headers().firstValue("location");
                    if (location.isPresent()) {
                        response.body().close();
                        if (++redirects > properties.getCurl().getMaxRedirects()) {
                            throw new UpstreamException("Curl redirect limit exceeded");
                        }
                        URI next = targetGuard.validate(current.resolve(location.get()).toString(), self);
                        upstreamLog.info("[OPENREACH-UPSTREAM] redirect provider=curl fromHost={} toHost={} status={} redirect={}",
                                current.getHost(), next.getHost(), status, redirects);
                        current = next;
                        continue;
                    }
                }

                String contentType = response.headers().firstValue("content-type").orElse("text/plain; charset=UTF-8");
                byte[] bytes;
                if ("HEAD".equals(method)) {
                    response.body().close();
                    bytes = new byte[0];
                } else {
                    ensureTextual(contentType);
                    bytes = BoundedBodyReader.read(response.body(), properties.getCurl().getMaxBytes(), "curl");
                }
                int maxChars = request.maxChars() == null
                        ? properties.getCurl().getMaxChars()
                        : Math.min(request.maxChars(), properties.getCurl().getMaxChars());
                String body = new String(bytes, resolveCharset(contentType));
                boolean truncated = body.length() > maxChars;
                if (truncated) body = body.substring(0, maxChars);

                CurlResponse result = new CurlResponse(
                        request.url(), current.toString(), method, status, contentType, body, truncated, redirects,
                        (System.nanoTime() - started) / 1_000_000L, sanitizeResponseHeaders(response.headers())
                );
                upstreamLog.info("[OPENREACH-CURL] curl_success method={} host={} status={} redirects={} truncated={} latencyMs={}",
                        method, current.getHost(), status, redirects, truncated, result.latencyMs());
                return result;
            }
        } catch (BadRequestException | UpstreamException ex) {
            upstreamLog.warn("[OPENREACH-CURL] curl_fail method={} host={} type={} message={}",
                    method, current.getHost(), ex.getClass().getSimpleName(), compact(ex.getMessage()));
            throw ex;
        } catch (IOException ex) {
            throw new UpstreamException("Curl upstream I/O failed: " + compact(ex.getMessage()), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("Curl request interrupted", ex);
        }
    }

    private HttpRequest buildRequest(URI uri, String method, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(Math.max(1, properties.getCurl().getRequestTimeoutMs())))
                .header("User-Agent", properties.getCurl().getUserAgent())
                .header("Accept", "text/plain,application/json,application/*+json,text/*,*/*;q=0.5")
                .header("Accept-Language", properties.getCurl().getAcceptLanguage());
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            builder.setHeader(entry.getKey(), entry.getValue());
        }
        return "HEAD".equals(method)
                ? builder.method("HEAD", HttpRequest.BodyPublishers.noBody()).build()
                : builder.GET().build();
    }

    private String normalizeMethod(String method) {
        String normalized = method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("GET") && !normalized.equals("HEAD")) {
            throw new BadRequestException("Curl v0.1.4 only allows read-only GET/HEAD requests");
        }
        return normalized;
    }

    private Map<String, String> validateHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return Map.of();
        Map<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (name.isBlank()) throw new BadRequestException("Curl request header name cannot be blank");
            String lower = name.toLowerCase(Locale.ROOT);
            if (FORBIDDEN_REQUEST_HEADERS.contains(lower) || lower.startsWith("x-forwarded-")) {
                throw new BadRequestException("Curl request header is forbidden: " + name);
            }
            if (containsControl(name) || containsControl(value)) {
                throw new BadRequestException("Curl request headers cannot contain control characters");
            }
            safe.put(name, value);
        }
        return safe;
    }

    private void ensureTextual(String contentType) {
        String lower = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean textual = lower.startsWith("text/")
                || lower.contains("json")
                || lower.contains("xml")
                || lower.contains("javascript")
                || lower.contains("yaml")
                || lower.contains("toml")
                || lower.contains("graphql")
                || lower.contains("x-www-form-urlencoded");
        if (!textual) {
            throw new BadRequestException("Curl only returns textual/source/API responses, got: " + contentType);
        }
    }

    private Charset resolveCharset(String contentType) {
        Matcher matcher = CHARSET_PATTERN.matcher(contentType == null ? "" : contentType);
        if (matcher.find()) {
            try { return Charset.forName(matcher.group(1).trim()); } catch (Exception ignored) { }
        }
        return StandardCharsets.UTF_8;
    }

    private Map<String, List<String>> sanitizeResponseHeaders(HttpHeaders headers) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, List<String>> entry : headers.map().entrySet()) {
            if (count >= 32) break;
            String lower = entry.getKey().toLowerCase(Locale.ROOT);
            if (HIDDEN_RESPONSE_HEADERS.contains(lower)) continue;
            List<String> values = new ArrayList<>();
            for (String value : entry.getValue()) {
                if (values.size() >= 8) break;
                String compact = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").trim();
                values.add(compact.length() <= 2048 ? compact : compact.substring(0, 2048));
            }
            result.put(entry.getKey(), List.copyOf(values));
            count++;
        }
        return Map.copyOf(result);
    }

    private boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x1F || c == 0x7F) return true;
        }
        return false;
    }

    private String safePath(URI uri) {
        String path = uri.getPath();
        return path == null || path.isBlank() ? "/" : path;
    }

    private String compact(String message) {
        if (message == null || message.isBlank()) return "unknown";
        String value = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    @FunctionalInterface
    interface HttpSender {
        HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;
    }
}
