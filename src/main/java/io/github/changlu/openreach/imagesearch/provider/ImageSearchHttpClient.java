package io.github.changlu.openreach.imagesearch.provider;

import io.github.changlu.openreach.common.BoundedBodyReader;
import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ImageSearchHttpClient {
    private static final Logger upstreamLog = LoggerFactory.getLogger("OPENREACH.UPSTREAM");
    private static final Pattern CHARSET_PATTERN = Pattern.compile("charset\\s*=\\s*[\\\"']?([^;\\s\\\"']+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CHARSET_PATTERN = Pattern.compile("<meta[^>]+charset\\s*=\\s*[\\\"']?([^\\s\\\"'/>]+)", Pattern.CASE_INSENSITIVE);

    private final WebCapabilityProperties properties;
    private final HttpClient client;

    public ImageSearchHttpClient(WebCapabilityProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getImageSearch().getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Document getDocument(String providerName, URI uri) {
        TextResponse response = getText(providerName, uri, Map.of());
        return Jsoup.parse(response.body(), response.uri().toString());
    }

    public TextResponse getText(String providerName, URI uri) {
        return getText(providerName, uri, Map.of());
    }

    public TextResponse getText(String providerName, URI uri, Map<String, String> extraHeaders) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(properties.getImageSearch().getTimeoutMs()))
                .header("User-Agent", properties.getImageSearch().getUserAgent())
                .header("Accept", "application/json,text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                .GET();
        extraHeaders.forEach(builder::setHeader);
        HttpRequest request = builder.build();
        long started = System.nanoTime();
        upstreamLog.info("[OPENREACH-UPSTREAM] http_start provider={} method={} host={} path={} kind=image-search",
                providerName, request.method(), request.uri().getHost(), request.uri().getPath());
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            upstreamLog.info("[OPENREACH-UPSTREAM] http_response provider={} status={} host={} latencyMs={} contentType={} retryAfter={} kind=image-search",
                    providerName, status, response.uri().getHost(), (System.nanoTime() - started) / 1_000_000L,
                    response.headers().firstValue("content-type").orElse("unknown"),
                    response.headers().firstValue("retry-after").orElse("none"));
            if (status < 200 || status >= 300) {
                response.body().close();
                throw new UpstreamException(providerName + " returned HTTP " + status);
            }
            int maxBytes = properties.getImageSearch().getMaxResponseBytes();
            long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (contentLength > Math.max(1024, maxBytes)) {
                response.body().close();
                throw new UpstreamException(providerName + " response body exceeds configured limit="
                        + Math.max(1024, maxBytes) + " bytes");
            }
            byte[] body = BoundedBodyReader.read(response.body(), maxBytes, providerName);
            String contentType = response.headers().firstValue("content-type").orElse("text/html; charset=utf-8");
            Charset charset = detectCharset(contentType, body);
            return new TextResponse(new String(body, charset), response.headers(), response.uri());
        } catch (IOException ex) {
            upstreamLog.warn("[OPENREACH-UPSTREAM] http_io_fail provider={} host={} latencyMs={} kind=image-search message={}",
                    providerName, request.uri().getHost(), (System.nanoTime() - started) / 1_000_000L, ex.getMessage());
            throw new UpstreamException(providerName + " request failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            upstreamLog.warn("[OPENREACH-UPSTREAM] http_interrupted provider={} host={} latencyMs={} kind=image-search",
                    providerName, request.uri().getHost(), (System.nanoTime() - started) / 1_000_000L);
            throw new UpstreamException(providerName + " request interrupted", ex);
        }
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

    public record TextResponse(String body, HttpHeaders headers, URI uri) {}
}
