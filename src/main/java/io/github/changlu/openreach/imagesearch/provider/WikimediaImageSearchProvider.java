package io.github.changlu.openreach.imagesearch.provider;

import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.imagesearch.ImageSearchProvider;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchItem;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Component
public class WikimediaImageSearchProvider implements ImageSearchProvider {
    private final ImageSearchHttpClient http;
    private final WebCapabilityProperties properties;
    private final JsonMapper objectMapper;

    public WikimediaImageSearchProvider(ImageSearchHttpClient http, WebCapabilityProperties properties, JsonMapper objectMapper) {
        this.http = http;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override public String name() { return "wikimedia"; }

    @Override
    public List<ImageSearchItem> search(String query, int limit, String region) {
        int requested = Math.min(Math.max(limit, 1), 30);
        URI uri = URI.create(properties.getImageSearch().getWikimediaUrl()
                + "?action=query&format=json&formatversion=2"
                + "&generator=search&gsrnamespace=6&gsrlimit=" + requested
                + "&gsrsearch=" + encode(query)
                + "&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiurlwidth=640");
        String body = http.getText(name(), uri, Map.of(
                "User-Agent", properties.getImageSearch().getWikimediaUserAgent(),
                "Accept", "application/json"
        )).body();
        List<ImageSearchItem> items = parseResults(body, limit);
        if (items.isEmpty()) throw new UpstreamException("wikimedia returned no parsable image results");
        return items;
    }

    List<ImageSearchItem> parseResults(String body, int limit) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode pages = root.path("query").path("pages");
            List<ImageSearchItem> result = new ArrayList<>();
            if (!pages.isArray()) return result;

            Iterator<JsonNode> iterator = pages.iterator();
            while (iterator.hasNext() && result.size() < limit) {
                JsonNode page = iterator.next();
                JsonNode infos = page.path("imageinfo");
                if (!infos.isArray() || infos.size() == 0) continue;
                JsonNode info = infos.get(0);

                String imageUrl = info.path("url").asText("");
                if (!ImageSearchProviderSupport.isHttp(imageUrl)) continue;
                String sourcePageUrl = info.path("descriptionurl").asText("");
                String thumbnailUrl = info.path("thumburl").asText("");
                JsonNode ext = info.path("extmetadata");

                String title = firstNonBlank(
                        metadata(ext, "ObjectName"),
                        metadata(ext, "ImageDescription"),
                        stripFilePrefix(page.path("title").asText(""))
                );
                String license = blankToNull(metadata(ext, "LicenseShortName"));
                String licenseUrl = httpOrNull(metadata(ext, "LicenseUrl"));
                String domain = ImageSearchProviderSupport.host(sourcePageUrl);
                if (domain.isBlank()) domain = "commons.wikimedia.org";

                result.add(new ImageSearchItem(
                        result.size() + 1,
                        ImageSearchProviderSupport.clean(title),
                        imageUrl,
                        httpOrNull(thumbnailUrl),
                        httpOrNull(sourcePageUrl),
                        name(),
                        "Wikimedia Commons",
                        domain,
                        nullableInt(info.get("width")),
                        nullableInt(info.get("height")),
                        format(info.path("mime").asText(""), imageUrl),
                        license,
                        licenseUrl
                ));
            }
            return result;
        } catch (Exception ex) {
            throw new UpstreamException("wikimedia JSON parse failed: " + ex.getMessage(), ex);
        }
    }

    private String metadata(JsonNode ext, String field) {
        JsonNode node = ext.path(field).path("value");
        return node.isMissingNode() || node.isNull() ? "" : node.asText("");
    }

    private Integer nullableInt(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.canConvertToInt()) return node.asInt();
        return ImageSearchProviderSupport.intOrNull(node.asText());
    }

    private String format(String mime, String imageUrl) {
        if (mime != null && mime.startsWith("image/")) {
            String value = mime.substring("image/".length()).toLowerCase();
            if ("jpeg".equals(value)) return "jpg";
            return value;
        }
        return ImageSearchProviderSupport.formatFromUrl(imageUrl);
    }

    private String stripFilePrefix(String title) {
        if (title == null) return "";
        return title.regionMatches(true, 0, "File:", 0, 5) ? title.substring(5).trim() : title.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private String httpOrNull(String value) { return ImageSearchProviderSupport.isHttp(value) ? value : null; }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
