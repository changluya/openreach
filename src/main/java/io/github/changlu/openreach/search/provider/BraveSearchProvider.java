package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.routing.RegionLocaleSupport;
import io.github.changlu.openreach.routing.SearchRoute;
import io.github.changlu.openreach.routing.SearchRouteResolver;
import io.github.changlu.openreach.search.SearchProvider;
import io.github.changlu.openreach.search.SearchTimeRange;
import io.github.changlu.openreach.search.dto.SearchItem;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class BraveSearchProvider implements SearchProvider {
    private final SearchHttpClient http;
    private final WebCapabilityProperties properties;
    private final SearchRouteResolver routeResolver;

    public BraveSearchProvider(SearchHttpClient http, WebCapabilityProperties properties, SearchRouteResolver routeResolver) {
        this.http = http;
        this.properties = properties;
        this.routeResolver = routeResolver;
    }

    @Override public String name() { return "brave"; }

    @Override
    public boolean supportsTimeRange() { return true; }

    @Override
    public List<SearchItem> search(String query, int limit, String region) {
        return search(query, limit, region, SearchTimeRange.ANY);
    }

    @Override
    public List<SearchItem> search(String query, int limit, String region, SearchTimeRange timeRange) {
        SearchRoute route = routeResolver.resolve(region);
        String country = route == SearchRoute.CN ? "cn" : RegionLocaleSupport.countryCode(region, route).toLowerCase(Locale.ROOT);
        if (route == SearchRoute.GLOBAL && (region == null || region.isBlank() || "auto".equalsIgnoreCase(region)
                || "global".equalsIgnoreCase(region) || "wt-wt".equalsIgnoreCase(region))) {
            country = "all";
        }
        String locale = RegionLocaleSupport.localeTag(region, route).toLowerCase(Locale.ROOT);
        StringBuilder target = new StringBuilder(properties.getSearch().getBraveUrl())
                .append("?q=").append(encode(query)).append("&source=web");
        String timeFilter = braveTimeFilter(timeRange);
        if (!timeFilter.isBlank()) target.append("&tf=").append(timeFilter);
        URI uri = URI.create(target.toString());
        Document doc = http.get(name(), uri, Map.of(
                "Accept-Language", RegionLocaleSupport.acceptLanguage(region, route),
                "Cookie", "safesearch=moderate; useLocation=0; summarizer=0; country=" + country + "; ui_lang=" + locale
        ));
        List<SearchItem> items = parseResults(doc, limit);
        if (items.isEmpty()) throw new UpstreamException("brave returned no parsable results");
        return items;
    }

    List<SearchItem> parseResults(Document doc, int limit) {
        List<SearchItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Element block : doc.select("div.snippet")) {
            Element title = block.selectFirst("div.title, .snippet-title");
            Element link = block.selectFirst("a[href]");
            if (title == null || link == null) continue;

            String url = normalize(link.attr("href"));
            String cleanTitle = SearchProviderSupport.clean(title.text());
            if (cleanTitle.isBlank() || !SearchProviderSupport.isHttp(url) || isBraveInternal(url) || !seen.add(url)) continue;

            Element content = block.selectFirst("div.content, .snippet-description, .description");
            String snippet = content == null ? "" : SearchProviderSupport.clean(content.text());
            items.add(new SearchItem(items.size() + 1, cleanTitle, url, snippet, name()));
            if (items.size() >= limit) break;
        }

        return items;
    }

    String braveTimeFilter(SearchTimeRange timeRange) {
        if (timeRange == null) return "";
        return switch (timeRange) {
            case DAY -> "pd";
            case WEEK -> "pw";
            case MONTH -> "pm";
            case YEAR -> "py";
            case ANY -> "";
        };
    }

    private String normalize(String href) {
        return SearchProviderSupport.absolute("https://search.brave.com", href);
    }

    private boolean isBraveInternal(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && (host.equals("search.brave.com") || host.endsWith(".search.brave.com"));
        } catch (Exception ignored) {
            return true;
        }
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
