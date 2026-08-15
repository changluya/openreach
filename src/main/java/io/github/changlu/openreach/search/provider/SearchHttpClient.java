package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
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
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(properties.getSearch().getTimeoutMs()))
                .header("User-Agent", properties.getSearch().getUserAgent())
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new UpstreamException(providerName + " returned HTTP " + response.statusCode());
            }
            String contentType = response.headers().firstValue("content-type").orElse("text/html");
            Charset charset = detectCharset(contentType, response.body());
            return Jsoup.parse(new String(response.body(), charset), response.uri().toString());
        } catch (IOException ex) {
            throw new UpstreamException(providerName + " request failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
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
}
