package io.github.changlu.openreach.imagesearch.provider;

import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.imagesearch.ImageSearchProvider;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchItem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class BingImageSearchProvider implements ImageSearchProvider {
    private final ImageSearchHttpClient http;
    private final WebCapabilityProperties properties;
    private final JsonMapper objectMapper;

    public BingImageSearchProvider(ImageSearchHttpClient http, WebCapabilityProperties properties, JsonMapper objectMapper) {
        this.http = http;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override public String name() { return "bing"; }

    @Override
    public List<ImageSearchItem> search(String query, int limit, String region) {
        String cc = region == null || region.isBlank() || "auto".equalsIgnoreCase(region)
                || "wt-wt".equalsIgnoreCase(region) ? "CN" : region;
        URI uri = URI.create(properties.getImageSearch().getBingUrl()
                + "?q=" + encode(query)
                + "&async=1&first=1&count=" + Math.max(35, limit)
                + "&setlang=zh-Hans&cc=" + encode(cc));
        Document doc = http.getDocument(name(), uri);
        List<ImageSearchItem> items = parseResults(doc, limit);
        if (items.isEmpty()) throw new UpstreamException("bing image search returned no parsable results");
        return items;
    }

    List<ImageSearchItem> parseResults(Document doc, int limit) {
        List<ImageSearchItem> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element link : doc.select("a.iusc[m]")) {
            try {
                JsonNode meta = objectMapper.readTree(link.attr("m"));
                String imageUrl = text(meta, "murl");
                String thumbnailUrl = text(meta, "turl");
                String sourcePageUrl = text(meta, "purl");
                if (!ImageSearchProviderSupport.isHttp(imageUrl) || !seen.add(imageUrl)) continue;
                String title = firstNonBlank(text(meta, "t"), text(meta, "desc"), link.attr("aria-label"));
                Integer width = firstInt(meta, "expw", "mw", "w");
                Integer height = firstInt(meta, "exph", "mh", "h");
                String domain = ImageSearchProviderSupport.host(sourcePageUrl);
                result.add(new ImageSearchItem(
                        result.size() + 1,
                        ImageSearchProviderSupport.clean(title),
                        imageUrl,
                        ImageSearchProviderSupport.isHttp(thumbnailUrl) ? thumbnailUrl : null,
                        ImageSearchProviderSupport.isHttp(sourcePageUrl) ? sourcePageUrl : null,
                        name(),
                        domain,
                        domain,
                        width,
                        height,
                        ImageSearchProviderSupport.formatFromUrl(imageUrl),
                        null,
                        null
                ));
                if (result.size() >= limit) break;
            } catch (Exception ignored) {
                // one malformed result must not break the whole SERP
            }
        }
        return result;
    }

    private String text(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? "" : child.asText("");
    }

    private Integer firstInt(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode child = node.get(field);
            if (child != null && child.canConvertToInt()) return child.asInt();
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
