package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.routing.RegionLocaleSupport;
import io.github.changlu.openreach.routing.SearchRoute;
import io.github.changlu.openreach.routing.SearchRouteResolver;
import io.github.changlu.openreach.search.SearchProvider;
import io.github.changlu.openreach.search.SearchTimeRange;
import io.github.changlu.openreach.search.dto.SearchItem;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
public class BingSearchProvider implements SearchProvider {
    private final SearchHttpClient http;
    private final WebCapabilityProperties properties;
    private final SearchRouteResolver routeResolver;

    public BingSearchProvider(SearchHttpClient http, WebCapabilityProperties properties, SearchRouteResolver routeResolver) {
        this.http = http;
        this.properties = properties;
        this.routeResolver = routeResolver;
    }

    @Override public String name() { return "bing"; }

    /** Bing free Web SERP has verified presets for 24h/week/month. */
    @Override public boolean supportsTimeRange() { return true; }

    @Override
    public boolean supportsTimeRange(SearchTimeRange timeRange) {
        if (timeRange == null || timeRange == SearchTimeRange.ANY) return true;
        return timeRange == SearchTimeRange.DAY
                || timeRange == SearchTimeRange.WEEK
                || timeRange == SearchTimeRange.MONTH;
    }

    @Override
    public List<SearchItem> search(String query, int limit, String region) {
        return search(query, limit, region, SearchTimeRange.ANY);
    }

    @Override
    public List<SearchItem> search(String query, int limit, String region, SearchTimeRange timeRange) {
        SearchTimeRange normalized = timeRange == null ? SearchTimeRange.ANY : timeRange;
        if (!supportsTimeRange(normalized)) {
            throw new BadRequestException("Search provider 'bing' does not have a verified free Web filter for timeRange="
                    + normalized.apiValue());
        }
        SearchRoute route = routeResolver.resolve(region);
        String baseUrl = baseUrl(route, normalized);
        URI uri = buildUri(query, region, normalized);
        Document doc = http.get(name(), uri, Map.of(
                "Accept-Language", RegionLocaleSupport.acceptLanguage(region, route)
        ));
        List<SearchItem> items = parseResults(doc, limit, origin(baseUrl));
        if (items.isEmpty()) throw new UpstreamException("bing returned no parsable results");
        return items;
    }

    URI buildUri(String query, String region) {
        return buildUri(query, region, SearchTimeRange.ANY);
    }

    URI buildUri(String query, String region, SearchTimeRange timeRange) {
        SearchRoute route = routeResolver.resolve(region);
        SearchTimeRange normalized = timeRange == null ? SearchTimeRange.ANY : timeRange;
        if (!supportsTimeRange(normalized)) {
            throw new BadRequestException("Search provider 'bing' does not have a verified free Web filter for timeRange="
                    + normalized.apiValue());
        }

        String cc = RegionLocaleSupport.countryCode(region, route);
        String locale = RegionLocaleSupport.localeTag(region, route);
        StringBuilder url = new StringBuilder(baseUrl(route, normalized))
                .append("?q=").append(encode(query))
                .append("&setlang=").append(encode(locale))
                .append("&cc=").append(encode(cc));

        String filter = bingTimeFilter(normalized);
        if (!filter.isBlank()) url.append("&filters=").append(encode(filter));
        return URI.create(url.toString());
    }

    /**
     * Current Bing Web date presets observed in the public result-page URL:
     * ez1=24 hours, ez2=week, ez3=month. No stable free-Web year preset was
     * verified, so YEAR is intentionally rejected rather than silently ignored.
     */
    String bingTimeFilter(SearchTimeRange timeRange) {
        if (timeRange == null) return "";
        return switch (timeRange) {
            case ANY -> "";
            case DAY -> "ex1:\"ez1\"";
            case WEEK -> "ex1:\"ez2\"";
            case MONTH -> "ex1:\"ez3\"";
            case YEAR -> throw new BadRequestException("Bing free Web search has no verified year filter");
        };
    }

    private String baseUrl(SearchRoute route, SearchTimeRange timeRange) {
        // Bing CN has recently been observed to omit/ignore the date-filter UI.
        // For restricted ranges use the international Web endpoint while keeping
        // region/language hints in cc/setlang. ANY preserves the original routing.
        if (timeRange != null && timeRange.isRestricted()) {
            return properties.getSearch().getBingGlobalUrl();
        }
        return route == SearchRoute.CN
                ? properties.getSearch().getBingUrl()
                : properties.getSearch().getBingGlobalUrl();
    }

    List<SearchItem> parseResults(Document doc, int limit) {
        return parseResults(doc, limit, "https://www.bing.com");
    }

    List<SearchItem> parseResults(Document doc, int limit, String baseUrl) {
        return SearchProviderSupport.parseBlocks(
                doc.select("li.b_algo, li.b_ans"),
                "h2 a, h3 a",
                ".b_caption p, .b_snippet, p",
                name(), limit,
                href -> SearchProviderSupport.absolute(baseUrl, href)
        );
    }

    private String origin(String url) {
        URI uri = URI.create(url);
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
