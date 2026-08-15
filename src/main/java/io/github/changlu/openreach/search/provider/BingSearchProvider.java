package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.search.SearchProvider;
import io.github.changlu.openreach.search.dto.SearchItem;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class BingSearchProvider implements SearchProvider {
    private final SearchHttpClient http;
    private final WebCapabilityProperties properties;

    public BingSearchProvider(SearchHttpClient http, WebCapabilityProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override public String name() { return "bing"; }

    @Override
    public List<SearchItem> search(String query, int limit, String region) {
        String cc = "CN".equalsIgnoreCase(region) || "wt-wt".equalsIgnoreCase(region) ? "CN" : region;
        URI uri = URI.create(properties.getSearch().getBingUrl()
                + "?q=" + encode(query) + "&setlang=zh-CN&cc=" + encode(cc));
        Document doc = http.get(name(), uri);
        List<SearchItem> items = parseResults(doc, limit);
        if (items.isEmpty()) throw new UpstreamException("bing returned no parsable results");
        return items;
    }

    List<SearchItem> parseResults(Document doc, int limit) {
        return SearchProviderSupport.parseBlocks(
                doc.select("li.b_algo, li.b_ans"),
                "h2 a, h3 a",
                ".b_caption p, .b_snippet, p",
                name(), limit,
                href -> SearchProviderSupport.absolute("https://cn.bing.com", href)
        );
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
