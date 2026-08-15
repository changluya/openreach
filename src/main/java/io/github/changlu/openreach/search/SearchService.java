package io.github.changlu.openreach.search;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.search.dto.SearchItem;
import io.github.changlu.openreach.search.dto.SearchRequest;
import io.github.changlu.openreach.search.dto.SearchResponse;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SearchService {
    private final Map<String, SearchProvider> providers;
    private final WebCapabilityProperties properties;

    public SearchService(List<SearchProvider> providers, WebCapabilityProperties properties) {
        this.providers = new LinkedHashMap<>();
        for (SearchProvider provider : providers) {
            this.providers.put(provider.name().toLowerCase(Locale.ROOT), provider);
        }
        this.properties = properties;
    }

    public SearchResponse search(SearchRequest request) {
        long started = System.nanoTime();
        int limit = request.effectiveLimit(properties.getSearch().getMaxResults());
        String region = request.effectiveRegion();
        String selected = request.effectiveProvider(properties.getSearch().getProvider());

        List<SearchItem> items;
        if ("auto".equals(selected)) {
            items = searchAuto(request.query(), limit, region);
        } else {
            items = searchOne(selected, request.query(), limit, region);
        }

        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        return new SearchResponse(selected, request.query(), region, items.size(), latencyMs, renumber(items, limit));
    }

    private List<SearchItem> searchAuto(String query, int limit, String region) {
        LinkedHashMap<String, SearchItem> merged = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (String providerName : properties.getSearch().getProviderOrder()) {
            SearchProvider provider = providers.get(providerName.toLowerCase(Locale.ROOT));
            if (provider == null) {
                errors.add(providerName + ": not registered");
                continue;
            }
            try {
                List<SearchItem> result = provider.search(query, limit, region);
                for (SearchItem item : result) {
                    String key = canonicalUrl(item.url());
                    if (!key.isBlank()) merged.putIfAbsent(key, item);
                    if (merged.size() >= limit) break;
                }
                if (merged.size() >= limit) {
                    break;
                }
            } catch (RuntimeException ex) {
                errors.add(provider.name() + ": " + compactMessage(ex));
            }
        }

        if (merged.isEmpty()) {
            throw new UpstreamException("All free search providers failed: " + String.join(" | ", errors));
        }
        return new ArrayList<>(merged.values());
    }

    private List<SearchItem> searchOne(String providerName, String query, int limit, String region) {
        SearchProvider provider = providers.get(providerName.toLowerCase(Locale.ROOT));
        if (provider == null) {
            throw new BadRequestException("Unsupported search provider: " + providerName
                    + ". Supported: auto," + String.join(",", providers.keySet()));
        }
        List<SearchItem> result = provider.search(query, limit, region);
        if (result == null || result.isEmpty()) {
            throw new UpstreamException(provider.name() + " returned no parsable results");
        }
        return result;
    }

    private List<SearchItem> renumber(List<SearchItem> items, int limit) {
        List<SearchItem> result = new ArrayList<>();
        for (SearchItem item : items) {
            if (result.size() >= limit) break;
            result.add(new SearchItem(result.size() + 1, item.title(), item.url(), item.snippet(), item.source()));
        }
        return result;
    }

    private String canonicalUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            URI uri = URI.create(raw.trim());
            String host = uri.getHost();
            if (host == null) return raw.trim();
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return (uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT))
                    + "://" + host.toLowerCase(Locale.ROOT) + path
                    + (uri.getQuery() == null ? "" : "?" + uri.getQuery());
        } catch (Exception ignored) {
            return raw.trim();
        }
    }

    private String compactMessage(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return ex.getClass().getSimpleName();
        return message.replaceAll("\\s+", " ").trim();
    }
}
