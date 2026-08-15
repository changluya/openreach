package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.routing.RegionLocaleSupport;
import io.github.changlu.openreach.routing.SearchRoute;
import io.github.changlu.openreach.routing.SearchRouteResolver;
import io.github.changlu.openreach.search.SearchProvider;
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

    @Override
    public List<SearchItem> search(String query, int limit, String region) {
        SearchRoute route = routeResolver.resolve(region);
        String baseUrl = baseUrl(route);
        URI uri = buildUri(query, region);
        Document doc = http.get(name(), uri, Map.of(
                "Accept-Language", RegionLocaleSupport.acceptLanguage(region, route)
        ));
        List<SearchItem> items = parseResults(doc, limit, origin(baseUrl));
        if (items.isEmpty()) throw new UpstreamException("bing returned no parsable results");
        return items;
    }

    URI buildUri(String query, String region) {
        SearchRoute route = routeResolver.resolve(region);
        String cc = RegionLocaleSupport.countryCode(region, route);
        String locale = RegionLocaleSupport.localeTag(region, route);
        return URI.create(baseUrl(route)
                + "?q=" + encode(query)
                + "&setlang=" + encode(locale)
                + "&cc=" + encode(cc));
    }

    private String baseUrl(SearchRoute route) {
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
