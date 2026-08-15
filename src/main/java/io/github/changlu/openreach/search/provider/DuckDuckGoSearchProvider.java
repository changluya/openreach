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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class DuckDuckGoSearchProvider implements SearchProvider {
    private final SearchHttpClient http;
    private final WebCapabilityProperties properties;
    private final SearchRouteResolver routeResolver;

    public DuckDuckGoSearchProvider(SearchHttpClient http, WebCapabilityProperties properties, SearchRouteResolver routeResolver) {
        this.http = http;
        this.properties = properties;
        this.routeResolver = routeResolver;
    }

    @Override public String name() { return "duckduckgo"; }

    @Override
    public boolean supportsTimeRange() { return true; }

    @Override
    public List<SearchItem> search(String query, int limit, String region) {
        return search(query, limit, region, SearchTimeRange.ANY);
    }

    @Override
    public List<SearchItem> search(String query, int limit, String region, SearchTimeRange timeRange) {
        if (query != null && query.length() > 499) {
            throw new UpstreamException("duckduckgo query exceeds 499 characters");
        }
        SearchRoute route = routeResolver.resolve(region);
        String kl = RegionLocaleSupport.duckDuckGoRegion(region, route);
        URI uri = URI.create(properties.getSearch().getDuckduckgoUrl());
        java.util.LinkedHashMap<String, String> form = new java.util.LinkedHashMap<>();
        form.put("q", query);
        form.put("b", "");
        form.put("kl", kl);
        String timeFilter = duckDuckGoTimeFilter(timeRange);
        if (!timeFilter.isBlank()) form.put("df", timeFilter);

        String cookie = "kl=" + kl + (timeFilter.isBlank() ? "" : "; df=" + timeFilter);
        Document doc = http.postForm(name(), uri,
                form,
                Map.of(
                        "Accept-Language", RegionLocaleSupport.acceptLanguage(region, route),
                        "Referer", "https://html.duckduckgo.com/",
                        "Sec-Fetch-Mode", "navigate",
                        "Cookie", cookie
                ));
        if (isCaptcha(doc)) throw new UpstreamException("duckduckgo bot challenge detected");
        List<SearchItem> items = parseResults(doc, limit);
        if (items.isEmpty()) throw new UpstreamException("duckduckgo returned no parsable results");
        return items;
    }

    List<SearchItem> parseResults(Document doc, int limit) {
        List<SearchItem> items = SearchProviderSupport.parseBlocks(
                doc.select(".result, .web-result"),
                "a.result__a, a.result-link",
                ".result__snippet, .result-snippet",
                name(), limit,
                this::normalizeResultUrl
        );
        if (!items.isEmpty()) return items;

        List<SearchItem> fallback = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element link : doc.select("a[href]")) {
            String title = SearchProviderSupport.clean(link.text());
            String url = normalizeResultUrl(link.attr("href"));
            if (title.length() < 3 || !SearchProviderSupport.isHttp(url) || isDuckDuckGo(url) || !seen.add(url)) continue;
            fallback.add(new SearchItem(fallback.size() + 1, title, url, "", name()));
            if (fallback.size() >= limit) break;
        }
        return fallback;
    }

    boolean isCaptcha(Document doc) {
        String text = doc.text().toLowerCase();
        return text.contains("not a robot") || text.contains("anomaly-modal") || doc.selectFirst("form#challenge-form") != null;
    }

    String duckDuckGoTimeFilter(SearchTimeRange timeRange) {
        if (timeRange == null) return "";
        return switch (timeRange) {
            case DAY -> "d";
            case WEEK -> "w";
            case MONTH -> "m";
            case YEAR -> "y";
            case ANY -> "";
        };
    }

    private String normalizeResultUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String value = raw.startsWith("//") ? "https:" + raw : raw;
        if (isDuckDuckGo(value)) {
            String decoded = SearchProviderSupport.decodeQueryParam(value, "uddg");
            if (!decoded.equals(value)) return decoded;
        }
        return SearchProviderSupport.absolute("https://html.duckduckgo.com", value);
    }

    private boolean isDuckDuckGo(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && host.endsWith("duckduckgo.com");
        } catch (Exception ignored) {
            return false;
        }
    }
}
