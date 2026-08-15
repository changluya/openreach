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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class SogouImageSearchProvider implements ImageSearchProvider {
    private static final String INITIAL_STATE_MARKER = "window.__INITIAL_STATE__";

    private final ImageSearchHttpClient http;
    private final WebCapabilityProperties properties;
    private final JsonMapper objectMapper;

    public SogouImageSearchProvider(ImageSearchHttpClient http, WebCapabilityProperties properties, JsonMapper objectMapper) {
        this.http = http;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override public String name() { return "sogou"; }

    @Override
    public List<ImageSearchItem> search(String query, int limit, String region) {
        URI uri = URI.create(properties.getImageSearch().getSogouUrl()
                + "?query=" + encode(query) + "&start=0");
        String body = http.getText(name(), uri).body();
        List<ImageSearchItem> items = parseResults(body, limit);
        if (items.isEmpty()) throw new UpstreamException("sogou image search returned no parsable results");
        return items;
    }

    List<ImageSearchItem> parseResults(String body, int limit) {
        String stateJson = extractInitialStateJson(body);
        if (stateJson == null) return List.of();
        try {
            JsonNode root = objectMapper.readTree(stateJson);
            JsonNode list = root.path("searchList").path("searchList");
            if (!list.isArray()) return List.of();
            List<ImageSearchItem> result = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode item : list) {
                String imageUrl = item.path("picUrl").asText("");
                String sourcePageUrl = item.path("url").asText("");
                if (!ImageSearchProviderSupport.isHttp(imageUrl) || !seen.add(imageUrl)) continue;
                String domain = ImageSearchProviderSupport.host(sourcePageUrl);
                String source = firstNonBlank(item.path("ch_site_name").asText(""), domain);
                result.add(new ImageSearchItem(
                        result.size() + 1,
                        ImageSearchProviderSupport.clean(firstNonBlank(item.path("title").asText(""), item.path("content_major").asText(""))),
                        imageUrl,
                        imageUrl,
                        ImageSearchProviderSupport.isHttp(sourcePageUrl) ? sourcePageUrl : null,
                        name(),
                        source,
                        domain,
                        firstInt(item, "width", "picWidth"),
                        firstInt(item, "height", "picHeight"),
                        ImageSearchProviderSupport.formatFromUrl(imageUrl),
                        null,
                        null
                ));
                if (result.size() >= limit) break;
            }
            return result;
        } catch (Exception ex) {
            throw new UpstreamException("sogou image state parse failed: " + ex.getMessage(), ex);
        }
    }


    /**
     * Extracts the JSON object assigned to window.__INITIAL_STATE__.
     *
     * A regex such as "\\{.*?\\}" is not safe here: the state contains nested objects/arrays
     * and strings may themselves contain braces. This scanner tracks JSON object depth while
     * respecting quoted strings and escapes, so startup is not coupled to a fragile static regex.
     */
    String extractInitialStateJson(String body) {
        if (body == null || body.isBlank()) return null;

        int marker = body.indexOf(INITIAL_STATE_MARKER);
        if (marker < 0) return null;

        int equals = body.indexOf('=', marker + INITIAL_STATE_MARKER.length());
        if (equals < 0) return null;

        int start = body.indexOf('{', equals + 1);
        if (start < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        char quote = 0;

        for (int i = start; i < body.length(); i++) {
            char ch = body.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == quote) {
                    inString = false;
                }
                continue;
            }

            if (ch == '"' || ch == '\'') {
                inString = true;
                quote = ch;
                continue;
            }

            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return body.substring(start, i + 1);
                }
            }
        }

        return null;
    }

    private Integer firstInt(JsonNode item, String... fields) {
        for (String field : fields) {
            JsonNode value = item.get(field);
            if (value != null && value.canConvertToInt()) return value.asInt();
            if (value != null) {
                Integer parsed = ImageSearchProviderSupport.intOrNull(value.asText());
                if (parsed != null) return parsed;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
