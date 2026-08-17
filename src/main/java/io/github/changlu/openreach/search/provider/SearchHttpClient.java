package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.common.BoundedBodyReader;
import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SearchHttpClient {
    private static final Logger upstreamLog = LoggerFactory.getLogger("OPENREACH.UPSTREAM");
    private static final Pattern CHARSET_PATTERN = Pattern.compile("charset\\s*=\\s*[\\\"']?([^;\\s\\\"']+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CHARSET_PATTERN = Pattern.compile("<meta[^>]+charset\\s*=\\s*[\\\"']?([^\\s\\\"'/>]+)", Pattern.CASE_INSENSITIVE);

    private final WebCapabilityProperties properties;
    private final HttpSender sender;

    @Autowired
    public SearchHttpClient(WebCapabilityProperties properties) {
        this.properties = properties;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getSearch().getTimeoutMs()))
                // Redirects are handled below so HTTPS -> HTTP provider redirects can be
                // safely upgraded back to HTTPS instead of surfacing as an unexplained 302.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.sender = request -> client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    SearchHttpClient(WebCapabilityProperties properties, HttpSender sender) {
        this.properties = properties;
        this.sender = sender;
    }

    public Document get(String providerName, URI uri) {
        return get(providerName, uri, Map.of());
    }

    public Document get(String providerName, URI uri, Map<String, String> extraHeaders) {
        HttpRequest.Builder builder = baseRequest(uri, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5");
        extraHeaders.forEach(builder::setHeader);
        return sendDocument(providerName, builder.GET().build());
    }

    public Document postForm(String providerName, URI uri, Map<String, String> form, Map<String, String> extraHeaders) {
        String body = encodeForm(form);
        HttpRequest.Builder builder = baseRequest(uri, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                .setHeader("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        extraHeaders.forEach(builder::setHeader);
        return sendDocument(providerName, builder.build());
    }

    private HttpRequest.Builder baseRequest(URI uri, String accept) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(properties.getSearch().getTimeoutMs()))
                .header("User-Agent", properties.getSearch().getUserAgent())
                .header("Accept", accept)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none");
    }

    private Document sendDocument(String providerName, HttpRequest initialRequest) {
        long started = System.nanoTime();
        HttpRequest request = initialRequest;
        int redirects = 0;

        while (true) {
            upstreamLog.info("[OPENREACH-UPSTREAM] http_start provider={} method={} host={} path={} redirect={}",
                    providerName, request.method(), request.uri().getHost(), request.uri().getPath(), redirects);
            try {
                HttpResponse<InputStream> response = sender.send(request);
                int status = response.statusCode();
                upstreamLog.info("[OPENREACH-UPSTREAM] http_response provider={} status={} host={} latencyMs={} contentType={} retryAfter={} redirect={}",
                        providerName, status, request.uri().getHost(), (System.nanoTime() - started) / 1_000_000L,
                        response.headers().firstValue("content-type").orElse("unknown"),
                        response.headers().firstValue("retry-after").orElse("none"), redirects);

                if (isRedirect(status)) {
                    Optional<String> location = response.headers().firstValue("location");
                    response.body().close();
                    if (location.isEmpty() || location.get().isBlank()) {
                        throw new UpstreamException(providerName + " returned HTTP " + status + " without Location header");
                    }
                    if (++redirects > Math.max(0, properties.getSearch().getMaxRedirects())) {
                        throw new UpstreamException(providerName + " exceeded search redirect limit="
                                + Math.max(0, properties.getSearch().getMaxRedirects()));
                    }

                    URI next = normalizeRedirect(providerName, request.uri(), request.uri().resolve(location.get()));
                    upstreamLog.info("[OPENREACH-UPSTREAM] redirect provider={} status={} fromHost={} toHost={} redirect={}",
                            providerName, status, request.uri().getHost(), next.getHost(), redirects);
                    request = redirectedRequest(request, status, next);
                    continue;
                }

                if (status < 200 || status >= 300) {
                    response.body().close();
                    throw new UpstreamException(providerName + " returned HTTP " + status);
                }
                int maxBytes = properties.getSearch().getMaxResponseBytes();
                long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
                if (contentLength > Math.max(1024, maxBytes)) {
                    response.body().close();
                    throw new UpstreamException(providerName + " response body exceeds configured limit="
                            + Math.max(1024, maxBytes) + " bytes");
                }
                byte[] body = BoundedBodyReader.read(response.body(), maxBytes, providerName);
                String contentType = response.headers().firstValue("content-type").orElse("text/html");
                Charset charset = detectCharset(contentType, body);
                return Jsoup.parse(new String(body, charset), request.uri().toString());
            } catch (IOException ex) {
                upstreamLog.warn("[OPENREACH-UPSTREAM] http_io_fail provider={} host={} latencyMs={} message={}",
                        providerName, request.uri().getHost(), (System.nanoTime() - started) / 1_000_000L, ex.getMessage());
                throw new UpstreamException(providerName + " request failed: " + ex.getMessage(), ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                upstreamLog.warn("[OPENREACH-UPSTREAM] http_interrupted provider={} host={} latencyMs={}",
                        providerName, request.uri().getHost(), (System.nanoTime() - started) / 1_000_000L);
                throw new UpstreamException(providerName + " request interrupted", ex);
            }
        }
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    /**
     * Free SERP endpoints can return redirects while moving between entry URLs or host variants.
     * JDK Redirect.NORMAL also refuses HTTPS -> HTTP downgrade redirects. Because the failure log
     * did not capture Location, we do not assume one specific 302 shape here: redirects are handled
     * explicitly, never downgraded to HTTP, bounded, and restricted to the provider's trusted domain.
     */
    URI normalizeRedirect(String providerName, URI current, URI next) {
        if (next == null || next.getHost() == null) {
            throw new UpstreamException(providerName + " returned invalid redirect target");
        }
        String scheme = next.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new UpstreamException(providerName + " returned unsupported redirect scheme: " + scheme);
        }
        if (!trustedRedirectHost(providerName, current.getHost(), next.getHost())) {
            throw new UpstreamException(providerName + " redirected outside trusted provider domain: " + next.getHost());
        }
        if ("https".equalsIgnoreCase(current.getScheme()) && "http".equalsIgnoreCase(next.getScheme())) {
            try {
                int port = next.getPort() == 80 ? -1 : next.getPort();
                return new URI("https", null, next.getHost(), port, next.getPath(), next.getQuery(), next.getFragment());
            } catch (Exception ex) {
                throw new UpstreamException(providerName + " returned invalid redirect target", ex);
            }
        }
        return next;
    }

    private boolean trustedRedirectHost(String providerName, String currentHost, String nextHost) {
        if (currentHost == null || nextHost == null) return false;
        String current = currentHost.toLowerCase(Locale.ROOT);
        String next = nextHost.toLowerCase(Locale.ROOT);
        if (current.equals(next)) return true;

        String trustedSuffix = switch (providerName == null ? "" : providerName.toLowerCase(Locale.ROOT)) {
            case "baidu" -> "baidu.com";
            case "bing" -> "bing.com";
            case "sogou" -> "sogou.com";
            case "so360" -> "so.com";
            case "brave" -> "brave.com";
            case "duckduckgo" -> "duckduckgo.com";
            default -> "";
        };
        return !trustedSuffix.isBlank()
                && inDomain(current, trustedSuffix)
                && inDomain(next, trustedSuffix);
    }

    private boolean inDomain(String host, String suffix) {
        return host.equals(suffix) || host.endsWith("." + suffix);
    }

    private HttpRequest redirectedRequest(HttpRequest previous, int status, URI next) {
        boolean switchToGet = status == 303
                || ((status == 301 || status == 302) && "POST".equalsIgnoreCase(previous.method()));
        HttpRequest.Builder builder = HttpRequest.newBuilder(next)
                .timeout(previous.timeout().orElse(Duration.ofMillis(properties.getSearch().getTimeoutMs())));
        previous.headers().map().forEach((name, values) -> {
            if (switchToGet && "content-type".equalsIgnoreCase(name)) return;
            values.forEach(value -> builder.header(name, value));
        });
        if (switchToGet) {
            return builder.GET().build();
        }
        return builder.method(previous.method(), previous.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody())).build();
    }

    private String encodeForm(Map<String, String> form) {
        Map<String, String> stable = new LinkedHashMap<>(form);
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : stable.entrySet()) {
            if (out.length() > 0) out.append('&');
            out.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue() == null ? "" : entry.getValue(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private Charset detectCharset(String contentType, byte[] body) {
        Matcher header = CHARSET_PATTERN.matcher(contentType == null ? "" : contentType);
        if (header.find()) return safeCharset(header.group(1));

        int len = Math.min(body.length, 8192);
        String prefix = new String(body, 0, len, StandardCharsets.ISO_8859_1);
        Matcher meta = META_CHARSET_PATTERN.matcher(prefix);
        if (meta.find()) return safeCharset(meta.group(1));
        return StandardCharsets.UTF_8;
    }

    private Charset safeCharset(String name) {
        try {
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            if ("gb2312".equals(normalized)) normalized = "GB18030";
            return Charset.forName(normalized);
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    @FunctionalInterface
    interface HttpSender {
        HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;
    }
}
