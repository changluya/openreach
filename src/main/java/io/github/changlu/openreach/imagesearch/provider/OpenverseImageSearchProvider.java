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
import java.util.ArrayList;
import java.util.List;

@Component
public class OpenverseImageSearchProvider implements ImageSearchProvider {
    private final ImageSearchHttpClient http;
    private final WebCapabilityProperties properties;
    private final JsonMapper objectMapper;

    public OpenverseImageSearchProvider(ImageSearchHttpClient http, WebCapabilityProperties properties, JsonMapper objectMapper) {
        this.http = http;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override public String name() { return "openverse"; }

    @Override
    public List<ImageSearchItem> search(String query, int limit, String region) {
        URI uri = URI.create(properties.getImageSearch().getOpenverseUrl()
                + "?q=" + encode(query) + "&page_size=" + Math.min(20, limit));
        String body = http.getText(name(), uri).body();
        List<ImageSearchItem> items = parseResults(body, limit);
        if (items.isEmpty()) throw new UpstreamException("openverse returned no parsable image results");
        return items;
    }

    List<ImageSearchItem> parseResults(String body, int limit) {
        try {
            JsonNode root = objectMapper.readTree(body);
            List<ImageSearchItem> result = new ArrayList<>();
            for (JsonNode item : root.path("results")) {
                String imageUrl = item.path("url").asText("");
                if (!ImageSearchProviderSupport.isHttp(imageUrl)) continue;
                String sourcePageUrl = item.path("foreign_landing_url").asText("");
                String source = firstNonBlank(item.path("source").asText(""), item.path("provider").asText(""), "openverse");
                result.add(new ImageSearchItem(
                        result.size() + 1,
                        ImageSearchProviderSupport.clean(item.path("title").asText("")),
                        imageUrl,
                        httpOrNull(item.path("thumbnail").asText("")),
                        httpOrNull(sourcePageUrl),
                        name(),
                        source,
                        ImageSearchProviderSupport.host(sourcePageUrl),
                        nullableInt(item.get("width")),
                        nullableInt(item.get("height")),
                        ImageSearchProviderSupport.formatFromUrl(imageUrl),
                        blankToNull(item.path("license").asText("")),
                        httpOrNull(item.path("license_url").asText(""))
                ));
                if (result.size() >= limit) break;
            }
            return result;
        } catch (Exception ex) {
            throw new UpstreamException("openverse JSON parse failed: " + ex.getMessage(), ex);
        }
    }

    private Integer nullableInt(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.canConvertToInt()) return node.asInt();
        return ImageSearchProviderSupport.intOrNull(node.asText());
    }

    private String httpOrNull(String value) { return ImageSearchProviderSupport.isHttp(value) ? value : null; }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private String firstNonBlank(String... values) { for (String v : values) if (v != null && !v.isBlank()) return v; return ""; }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
