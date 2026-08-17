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
public class So360SearchProvider implements SearchProvider {
    private final SearchHttpClient http;
    private final WebCapabilityProperties properties;

    public So360SearchProvider(SearchHttpClient http, WebCapabilityProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override public String name() { return "so360"; }

    @Override
    public List<SearchItem> search(String query, int limit, String region) {
        URI uri = buildUri(query);
        Document doc = http.get(name(), uri, Map.of("Referer", "https://www.so.com/"));
        List<SearchItem> items = parseResults(doc, limit);
        if (items.isEmpty()) throw new UpstreamException("so360 returned no parsable results");
        return items;
    }

    URI buildUri(String query) {
        String separator = properties.getSearch().getSo360Url().contains("?") ? "&" : "?";
        return URI.create(properties.getSearch().getSo360Url()
                + separator + "ie=utf-8&q=" + encode(query));
    }

    List<SearchItem> parseResults(Document doc, int limit) {
        return SearchProviderSupport.parseBlocks(
                doc.select("li.res-list, .res-list, .result"),
                "h3 a, .res-title a, a.res-title",
                ".res-desc, .summary, .res-comm-con, .res-rich-desc",
                name(), limit,
                href -> SearchProviderSupport.absolute("https://www.so.com", href)
        );
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
