package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.search.SearchProvider;
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
import java.util.Set;

@Component
public class DuckDuckGoSearchProvider implements SearchProvider {
    private final SearchHttpClient http;
    private final WebCapabilityProperties properties;

    public DuckDuckGoSearchProvider(SearchHttpClient http, WebCapabilityProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override public String name() { return "duckduckgo"; }

    @Override
    public List<SearchItem> search(String query, int limit, String region) {
        String kl = region == null || region.isBlank() || "auto".equalsIgnoreCase(region)
                ? "wt-wt"
                : ("CN".equalsIgnoreCase(region) ? "cn-zh" : region);
        URI uri = URI.create(properties.getSearch().getDuckduckgoUrl()
                + "?q=" + encode(query) + "&kl=" + encode(kl));
        Document doc = http.get(name(), uri);
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

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
