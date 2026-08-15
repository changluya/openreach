package io.github.changlu.openreach.imagesearch.provider;

import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.imagesearch.ImageSearchProvider;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchItem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class BaiduImageSearchProvider implements ImageSearchProvider {
    private final ImageSearchHttpClient http;
    private final WebCapabilityProperties properties;
    private final JsonMapper objectMapper;
    private volatile String cachedCookie = "";
    private volatile Instant cookieExpiresAt = Instant.EPOCH;

    public BaiduImageSearchProvider(ImageSearchHttpClient http, WebCapabilityProperties properties, JsonMapper objectMapper) {
        this.http = http;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override public String name() { return "baidu"; }

    @Override
    public List<ImageSearchItem> search(String query, int limit, String region) {
        String cookie = getCookie();
        URI uri = URI.create(properties.getImageSearch().getBaiduUrl()
                + "?word=" + encode(query)
                + "&rn=" + Math.max(10, limit)
                + "&pn=0&tn=resultjson_com");
        Map<String, String> headers = cookie.isBlank() ? Map.of() : Map.of("Cookie", cookie, "Referer", properties.getImageSearch().getBaiduBaseUrl());
        String body = http.getText(name(), uri, headers).body();
        List<ImageSearchItem> items = parseResults(body, limit);
        if (items.isEmpty()) throw new UpstreamException("baidu image search returned no parsable results");
        return items;
    }

    List<ImageSearchItem> parseResults(String body, int limit) {
        try {
            JsonNode root = objectMapper.readTree(body.replace("\\'", "'"));
            if (root.path("antiFlag").asInt(0) == 1) {
                throw new UpstreamException("baidu image search denied spider access: " + root.path("message").asText("antiFlag=1"));
            }
            List<ImageSearchItem> result = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode item : root.path("data")) {
                if (item == null || item.isEmpty()) continue;
                JsonNode replace = item.path("replaceUrl");
                JsonNode first = replace.isArray() && !replace.isEmpty() ? replace.get(0) : null;
                String imageUrl = first == null ? "" : first.path("ObjURL").asText("");
                String sourcePageUrl = first == null ? "" : first.path("FromURL").asText("");
                String thumbnailUrl = item.path("thumbURL").asText("");
                if (!ImageSearchProviderSupport.isHttp(imageUrl) || !seen.add(imageUrl)) continue;
                String domain = firstNonBlank(item.path("fromURLHost").asText(""), ImageSearchProviderSupport.host(sourcePageUrl));
                result.add(new ImageSearchItem(
                        result.size() + 1,
                        ImageSearchProviderSupport.clean(item.path("fromPageTitle").asText("")),
                        imageUrl,
                        ImageSearchProviderSupport.isHttp(thumbnailUrl) ? thumbnailUrl : null,
                        ImageSearchProviderSupport.isHttp(sourcePageUrl) ? sourcePageUrl : null,
                        name(),
                        domain,
                        domain,
                        nullableInt(item.get("width")),
                        nullableInt(item.get("height")),
                        blankToNull(item.path("type").asText("")),
                        null,
                        null
                ));
                if (result.size() >= limit) break;
            }
            return result;
        } catch (UpstreamException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new UpstreamException("baidu image JSON parse failed: " + ex.getMessage(), ex);
        }
    }

    private synchronized String getCookie() {
        if (!cachedCookie.isBlank() && Instant.now().isBefore(cookieExpiresAt)) return cachedCookie;
        try {
            var response = http.getText(name() + "-warmup", URI.create(properties.getImageSearch().getBaiduBaseUrl()));
            List<String> cookies = response.headers().allValues("set-cookie");
            cachedCookie = cookies.stream()
                    .map(value -> value.split(";", 2)[0])
                    .filter(value -> !value.isBlank())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");
            cookieExpiresAt = Instant.now().plusSeconds(3600);
        } catch (RuntimeException ignored) {
            cachedCookie = "";
            cookieExpiresAt = Instant.now().plusSeconds(60);
        }
        return cachedCookie;
    }

    private Integer nullableInt(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.canConvertToInt()) return node.asInt();
        return ImageSearchProviderSupport.intOrNull(node.asText());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
