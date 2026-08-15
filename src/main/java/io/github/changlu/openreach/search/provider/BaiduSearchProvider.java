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
public class BaiduSearchProvider implements SearchProvider {
    private final SearchHttpClient http;
    private final WebCapabilityProperties properties;

    public BaiduSearchProvider(SearchHttpClient http, WebCapabilityProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override public String name() { return "baidu"; }

    @Override
    public List<SearchItem> search(String query, int limit, String region) {
        URI uri = URI.create(properties.getSearch().getBaiduUrl()
                + "?wd=" + encode(query) + "&ie=utf-8&rn=" + Math.max(10, limit));
        Document doc = http.get(name(), uri);
        List<SearchItem> items = parseResults(doc, limit);
        if (items.isEmpty()) throw new UpstreamException("baidu returned no parsable results");
        return items;
    }

    List<SearchItem> parseResults(Document doc, int limit) {
        return SearchProviderSupport.parseBlocks(
                doc.select("#content_left .result, #content_left .c-container, #content_left .result-op, .result.c-container"),
                "h3 a, .t a",
                ".c-abstract, .c-span-last, .content-right_8Zs40, .c-font-normal, .abstract",
                name(), limit,
                href -> SearchProviderSupport.absolute("https://www.baidu.com", href)
        );
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
