package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.search.SearchProvider;
import io.github.changlu.openreach.search.SearchTimeRange;
import io.github.changlu.openreach.search.dto.SearchItem;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@Component
public class BaiduSearchProvider implements SearchProvider {
    private static final long DAY_SECONDS = 24L * 60 * 60;
    private final SearchHttpClient http;
    private final WebCapabilityProperties properties;

    public BaiduSearchProvider(SearchHttpClient http, WebCapabilityProperties properties) {
        this.http = http;
        this.properties = properties;
    }

    @Override public String name() { return "baidu"; }

    @Override public boolean supportsTimeRange() { return true; }

    @Override
    public List<SearchItem> search(String query, int limit, String region) {
        return search(query, limit, region, SearchTimeRange.ANY);
    }

    @Override
    public List<SearchItem> search(String query, int limit, String region, SearchTimeRange timeRange) {
        URI uri = buildUri(query, limit, timeRange, Instant.now().getEpochSecond());
        Document doc = http.get(name(), uri);
        if (isCaptcha(doc)) throw new UpstreamException("baidu bot challenge detected");
        List<SearchItem> items = parseResults(doc, limit);
        if (items.isEmpty()) throw new UpstreamException("baidu returned no parsable results");
        return items;
    }

    URI buildUri(String query, int limit, SearchTimeRange timeRange, long endEpochSecond) {
        SearchTimeRange normalized = timeRange == null ? SearchTimeRange.ANY : timeRange;
        String queryParam = normalized.isRestricted() ? "word" : "wd";
        StringBuilder url = new StringBuilder(properties.getSearch().getBaiduUrl())
                .append("?").append(queryParam).append("=").append(encode(query))
                .append("&ie=utf-8&rn=").append(Math.max(10, limit));

        if (normalized.isRestricted()) {
            long startEpochSecond = endEpochSecond - rangeSeconds(normalized);
            String gpc = "stf=" + startEpochSecond + "," + endEpochSecond + "|stftype=1";
            url.append("&gpc=").append(encode(gpc))
                    .append("&tfflag=1")
                    .append("&timefactor=").append(timeFactor(normalized));
        }
        return URI.create(url.toString());
    }

    long rangeSeconds(SearchTimeRange timeRange) {
        return switch (timeRange) {
            case DAY -> DAY_SECONDS;
            case WEEK -> 7L * DAY_SECONDS;
            case MONTH -> 30L * DAY_SECONDS;
            case YEAR -> 365L * DAY_SECONDS;
            case ANY -> 0L;
        };
    }

    int timeFactor(SearchTimeRange timeRange) {
        return switch (timeRange) {
            case DAY -> 21;
            case WEEK -> 22;
            case MONTH -> 23;
            case YEAR -> 24;
            case ANY -> 0;
        };
    }

    boolean isCaptcha(Document doc) {
        if (doc == null) return false;
        String location = doc.location() == null ? "" : doc.location().toLowerCase();
        if (location.contains("wappass.baidu.com/static/captcha") || location.contains("captcha")) return true;
        String title = doc.title();
        String text = doc.text();
        return (title != null && title.contains("百度安全验证"))
                || (text != null && (text.contains("百度安全验证") || text.contains("安全验证")));
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
