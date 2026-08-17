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
import java.util.Map;

@Component
public class SogouSearchProvider implements SearchProvider {
    private final SearchHttpClient http;
    private final WebCapabilityProperties properties;

    public SogouSearchProvider(SearchHttpClient http, WebCapabilityProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override public String name() { return "sogou"; }

    @Override
    public List<SearchItem> search(String query, int limit, String region) {
        URI uri = buildUri(query);
        Document doc = http.get(name(), uri, Map.of("Referer", "https://www.sogou.com/"));
        List<SearchItem> items = parseResults(doc, limit);
        if (items.isEmpty()) throw new UpstreamException("sogou returned no parsable results");
        return items;
    }

    URI buildUri(String query) {
        String separator = properties.getSearch().getSogouUrl().contains("?") ? "&" : "?";
        return URI.create(properties.getSearch().getSogouUrl()
                + separator + "query=" + encode(query) + "&ie=utf8");
    }

    List<SearchItem> parseResults(Document doc, int limit) {
        return SearchProviderSupport.parseBlocks(
                doc.select(".vrwrap, .rb, .results > div, .vr-result"),
                "h3 a, .vr-title a, a.vr-title",
                ".str_info, .text-layout, .ft, .fz-mid, .str-text-info",
                name(), limit,
                href -> SearchProviderSupport.absolute("https://www.sogou.com", href)
        );
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
