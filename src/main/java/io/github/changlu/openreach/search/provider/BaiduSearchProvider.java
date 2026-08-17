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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        List<SearchItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (var block : doc.select("#content_left .result, #content_left .c-container, #content_left .result-op, .result.c-container")) {
            var link = block.selectFirst("h3 a, .t a");
            if (link == null) continue;
            String title = SearchProviderSupport.clean(link.text());
            String url = preferredResultUrl(block, link);
            if (title.isBlank() || url.isBlank() || !SearchProviderSupport.isHttp(url) || !seen.add(url)) continue;
            var snippetEl = block.selectFirst(".c-abstract, .c-span-last, .content-right_8Zs40, .c-font-normal, .abstract");
            String snippet = snippetEl == null ? "" : SearchProviderSupport.clean(snippetEl.text());
            items.add(new SearchItem(items.size() + 1, title, url, snippet, name()));
            if (items.size() >= limit) break;
        }
        return items;
    }

    String preferredResultUrl(org.jsoup.nodes.Element block, org.jsoup.nodes.Element link) {
        String[] candidates = {
                link.attr("data-landurl"),
                link.attr("data-url"),
                block.attr("mu"),
                block.attr("data-url"),
                link.attr("href")
        };
        for (String candidate : candidates) {
            String normalized = normalizeBaiduResultUrl(candidate);
            if (!normalized.isBlank() && SearchProviderSupport.isHttp(normalized)) return normalized;
        }
        return "";
    }

    String normalizeBaiduResultUrl(String raw) {
        String absolute = SearchProviderSupport.absolute("https://www.baidu.com", raw);
        if (absolute.isBlank()) return "";
        try {
            URI uri = URI.create(absolute);
            String host = uri.getHost();
            if (host != null && (host.equalsIgnoreCase("baidu.com") || host.toLowerCase().endsWith(".baidu.com"))
                    && "/link".equals(uri.getPath()) && "http".equalsIgnoreCase(uri.getScheme())) {
                return new URI("https", null, uri.getHost(), uri.getPort() == 80 ? -1 : uri.getPort(),
                        uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
            }
            return absolute;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
