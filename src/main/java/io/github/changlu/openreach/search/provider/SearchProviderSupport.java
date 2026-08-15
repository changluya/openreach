package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.search.dto.SearchItem;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

final class SearchProviderSupport {
    private SearchProviderSupport() {}

    static List<SearchItem> parseBlocks(
            Iterable<Element> blocks,
            String linkSelector,
            String snippetSelector,
            String source,
            int limit,
            Function<String, String> urlNormalizer) {
        List<SearchItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element block : blocks) {
            Element link = block.selectFirst(linkSelector);
            if (link == null) continue;
            String title = clean(link.text());
            String url = urlNormalizer.apply(link.attr("href"));
            if (title.isBlank() || url.isBlank() || !isHttp(url) || !seen.add(url)) continue;
            Element snippetEl = snippetSelector == null ? null : block.selectFirst(snippetSelector);
            String snippet = snippetEl == null ? "" : clean(snippetEl.text());
            items.add(new SearchItem(items.size() + 1, title, url, snippet, source));
            if (items.size() >= limit) break;
        }
        return items;
    }

    static String absolute(String baseUrl, String href) {
        if (href == null || href.isBlank()) return "";
        try {
            return URI.create(baseUrl).resolve(href.trim()).toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    static String decodeQueryParam(String rawUrl, String paramName) {
        if (rawUrl == null || rawUrl.isBlank()) return "";
        try {
            URI uri = URI.create(rawUrl.startsWith("//") ? "https:" + rawUrl : rawUrl);
            String query = uri.getRawQuery();
            if (query == null) return rawUrl;
            for (String pair : query.split("&")) {
                int idx = pair.indexOf('=');
                if (idx > 0 && paramName.equals(pair.substring(0, idx))) {
                    return URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                }
            }
            return rawUrl;
        } catch (Exception ignored) {
            return "";
        }
    }

    static boolean isHttp(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (Exception ignored) {
            return false;
        }
    }

    static String clean(String value) {
        return value == null ? "" : value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }
}
