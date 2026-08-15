package io.github.changlu.openreach.search;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.search.dto.SearchItem;
import io.github.changlu.openreach.search.dto.SearchRequest;
import io.github.changlu.openreach.search.dto.SearchResponse;
import io.github.changlu.openreach.routing.ProviderChainResolver;
import io.github.changlu.openreach.routing.SearchRoute;
import io.github.changlu.openreach.routing.SearchRouteResolver;
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
    private final SearchRouteResolver routeResolver;
    private final ProviderChainResolver chainResolver;

    public SearchService(List<SearchProvider> providers, WebCapabilityProperties properties,
                         SearchRouteResolver routeResolver, ProviderChainResolver chainResolver) {
        this.providers = new LinkedHashMap<>();
        for (SearchProvider provider : providers) {
            this.providers.put(provider.name().toLowerCase(Locale.ROOT), provider);
        }
        this.properties = properties;
        this.routeResolver = routeResolver;
        this.chainResolver = chainResolver;
    }

    public SearchResponse search(SearchRequest request) {
        long started = System.nanoTime();
        int limit = request.effectiveLimit(properties.getSearch().getMaxResults());
        String region = request.effectiveRegion();
        String selected = request.effectiveProvider(properties.getSearch().getProvider());
        SearchTimeRange timeRange = request.effectiveTimeRange();

        SearchRoute route = routeResolver.resolve(region);

        List<SearchItem> items;
        if ("auto".equals(selected)) {
            items = searchAuto(request.query(), limit, region, timeRange, chainResolver.searchProviders(route));
        } else {
            items = searchOne(selected, request.query(), limit, region, timeRange);
        }

        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        return new SearchResponse(selected, request.query(), region, timeRange.apiValue(), items.size(), latencyMs, renumber(items, limit));
    }

    private List<SearchItem> searchAuto(String query, int limit, String region, SearchTimeRange timeRange,
                                        List<String> providerOrder) {
        LinkedHashMap<String, SearchItem> merged = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        if (providerOrder == null || providerOrder.isEmpty()) {
            throw new UpstreamException("No free search providers configured for the selected route");
        }

        for (String configuredName : providerOrder) {
            String providerName = normalizeProviderName(configuredName);
            if (providerName.isBlank()) {
                errors.add("<blank>: invalid provider name");
                continue;
            }
            SearchProvider provider = providers.get(providerName);
            if (provider == null) {
                errors.add(providerName + ": not registered");
                continue;
            }
            if (timeRange.isRestricted() && !provider.supportsTimeRange()) {
                errors.add(provider.name() + ": skipped (timeRange unsupported)");
                continue;
            }
            try {
                List<SearchItem> result = provider.search(query, limit, region, timeRange);
                if (result == null || result.isEmpty()) {
                    errors.add(provider.name() + ": empty result");
                    continue;
                }
                for (SearchItem item : result) {
                    if (item == null) continue;
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


    private List<SearchItem> searchOne(String providerName, String query, int limit, String region,
                                      SearchTimeRange timeRange) {
        SearchProvider provider = providers.get(providerName.toLowerCase(Locale.ROOT));
        if (provider == null) {
            throw new BadRequestException("Unsupported search provider: " + providerName
                    + ". Supported: auto," + String.join(",", providers.keySet()));
        }
        if (timeRange.isRestricted() && !provider.supportsTimeRange()) {
            throw new BadRequestException("Search provider '" + provider.name() + "' does not support timeRange. "
                    + "Use provider=auto, brave or duckduckgo.");
        }
        List<SearchItem> result = provider.search(query, limit, region, timeRange);
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

    private String normalizeProviderName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String compactMessage(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return ex.getClass().getSimpleName();
        return message.replaceAll("\\s+", " ").trim();
    }
}
