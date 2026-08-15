package io.github.changlu.openreach.imagesearch.provider;

import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.imagesearch.ImageSearchProvider;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchItem;
import io.github.changlu.openreach.routing.RegionLocaleSupport;
import io.github.changlu.openreach.routing.SearchRoute;
import io.github.changlu.openreach.routing.SearchRouteResolver;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class BingImageSearchProvider implements ImageSearchProvider {
    private final ImageSearchHttpClient http;
    private final WebCapabilityProperties properties;
    private final JsonMapper objectMapper;
    private final SearchRouteResolver routeResolver;

    public BingImageSearchProvider(ImageSearchHttpClient http, WebCapabilityProperties properties,
                                   JsonMapper objectMapper, SearchRouteResolver routeResolver) {
        this.http = http;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.routeResolver = routeResolver;
    }

    @Override public String name() { return "bing"; }

    @Override
    public List<ImageSearchItem> search(String query, int limit, String region) {
        SearchRoute route = routeResolver.resolve(region);
        String baseUrl = baseUrl(route);
        URI uri = buildUri(query, limit, region);
        String body = http.getText(name(), uri, Map.of(
                "Accept-Language", RegionLocaleSupport.acceptLanguage(region, route)
        )).body();
        Document doc = org.jsoup.Jsoup.parse(body, origin(baseUrl));
        List<ImageSearchItem> items = parseResults(doc, limit);
        if (items.isEmpty()) throw new UpstreamException("bing image search returned no parsable results");
        return items;
    }

    URI buildUri(String query, int limit, String region) {
        SearchRoute route = routeResolver.resolve(region);
        String cc = RegionLocaleSupport.countryCode(region, route);
        String locale = RegionLocaleSupport.localeTag(region, route);
        return URI.create(baseUrl(route)
                + "?q=" + encode(query)
                + "&async=1&first=1&count=" + Math.max(35, limit)
                + "&setlang=" + encode(locale)
                + "&cc=" + encode(cc));
    }

    private String baseUrl(SearchRoute route) {
        return route == SearchRoute.CN
                ? properties.getImageSearch().getBingUrl()
                : properties.getImageSearch().getBingGlobalUrl();
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

    private String origin(String url) {
        URI uri = URI.create(url);
        return uri.getScheme() + "://" + uri.getAuthority();
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
