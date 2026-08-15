package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.common.BoundedBodyReader;
import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SearchHttpClient {
    private static final Pattern CHARSET_PATTERN = Pattern.compile("charset\\s*=\\s*[\\\"']?([^;\\s\\\"']+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CHARSET_PATTERN = Pattern.compile("<meta[^>]+charset\\s*=\\s*[\\\"']?([^\\s\\\"'/>]+)", Pattern.CASE_INSENSITIVE);

    private final WebCapabilityProperties properties;
    private final HttpClient client;

    public SearchHttpClient(WebCapabilityProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getSearch().getTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
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
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6");
    }

    private Document sendDocument(String providerName, HttpRequest request) {
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new UpstreamException(providerName + " returned HTTP " + response.statusCode());
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
            return Jsoup.parse(new String(body, charset), response.uri().toString());
        } catch (IOException ex) {
            throw new UpstreamException(providerName + " request failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(providerName + " request interrupted", ex);
        }
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
}
