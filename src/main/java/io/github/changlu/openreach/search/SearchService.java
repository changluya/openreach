package io.github.changlu.openreach.search;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.observability.UpstreamFailureClassifier;
import io.github.changlu.openreach.routing.ProviderChainResolver;
import io.github.changlu.openreach.routing.SearchRoute;
import io.github.changlu.openreach.routing.SearchRouteResolver;
import io.github.changlu.openreach.search.dto.SearchItem;
import io.github.changlu.openreach.search.dto.SearchRequest;
import io.github.changlu.openreach.search.dto.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SearchService {
    private static final Logger upstreamLog = LoggerFactory.getLogger("OPENREACH.UPSTREAM");
    private final Map<String, SearchProvider> providers;
    private final WebCapabilityProperties properties;
    private final SearchRouteResolver routeResolver;
    private final ProviderChainResolver chainResolver;
    private final Map<String, Long> providerCooldownUntilMs = new ConcurrentHashMap<>();

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

    @PostConstruct
    void logRuntimeCapabilities() {
        upstreamLog.info("[OPENREACH-SEARCH] runtime_capabilities registered={} day={} week={} month={} year={}",
                new ArrayList<>(providers.keySet()),
                capableProviders(SearchTimeRange.DAY),
                capableProviders(SearchTimeRange.WEEK),
                capableProviders(SearchTimeRange.MONTH),
                capableProviders(SearchTimeRange.YEAR));
    }

    public SearchResponse search(SearchRequest request) {
        long started = System.nanoTime();
        int limit = request.effectiveLimit(properties.getSearch().getMaxResults());
        String region = request.effectiveRegion();
        String selected = request.effectiveProvider(properties.getSearch().getProvider());
        SearchTimeRange timeRange = request.effectiveTimeRange();
        SearchRoute route = routeResolver.resolve(region);

        upstreamLog.info("[OPENREACH-SEARCH] search_start provider={} route={} region={} timeRange={} limit={} queryLen={}",
                selected, route, region, timeRange.apiValue(), limit, safeLength(request.query()));

        try {
            List<String> providerOrder = chainResolver.searchProviders(route, timeRange.isRestricted());
            if ("auto".equals(selected) && timeRange.isRestricted()) {
                providerOrder = ensureTimeRangeCapableFallbacks(providerOrder, route, timeRange);
                upstreamLog.info("[OPENREACH-SEARCH] provider_chain route={} timeRange={} providers={}",
                        route, timeRange.apiValue(), providerOrder);
            }
            List<SearchItem> items = "auto".equals(selected)
                    ? searchAuto(request.query(), limit, region, timeRange, providerOrder)
                    : searchOne(selected, request.query(), limit, region, timeRange);
            long latencyMs = elapsedMs(started);
            upstreamLog.info("[OPENREACH-SEARCH] search_success provider={} count={} latencyMs={}", selected, items.size(), latencyMs);
            return new SearchResponse(selected, request.query(), region, timeRange.apiValue(), items.size(), latencyMs, renumber(items, limit));
        } catch (RuntimeException ex) {
            upstreamLog.error("[OPENREACH-SEARCH] search_fail type={} latencyMs={} message={}",
                    UpstreamFailureClassifier.classify(ex), elapsedMs(started), compactMessage(ex));
            throw ex;
        }
    }

    /**
     * timeRange is a hard capability requirement.  Do not trust only the configured
     * order because an older external application.yml (or a stale deployment config)
     * may still contain an early v0.1.2 DuckDuckGo/Brave-only chain. That exact legacy
     * shape is auto-migrated to the verified route-specific built-ins; otherwise operator
     * order is preserved and missing time-aware providers are appended as safety fallbacks.
     */
    private List<String> ensureTimeRangeCapableFallbacks(List<String> configuredOrder, SearchRoute route, SearchTimeRange timeRange) {
        LinkedHashSet<String> configured = new LinkedHashSet<>();
        if (configuredOrder != null) {
            for (String configuredName : configuredOrder) {
                String normalized = normalizeProviderName(configuredName);
                if (!normalized.isBlank()) configured.add(normalized);
            }
        }

        List<String> preferred = route == SearchRoute.GLOBAL
                ? List.of("bing", "brave", "duckduckgo", "baidu")
                : List.of("baidu", "bing", "duckduckgo", "brave");

        // v0.1.2 early builds used only duckduckgo/brave for restricted searches.
        // Treat that exact legacy pair as a migration signal and restore the verified
        // Bing/Baidu free-Web providers ahead of the frequently rate-limited fallbacks.
        boolean legacyTwoProviderTimeChain = !configured.isEmpty()
                && configured.size() <= 2
                && configured.stream().allMatch(name -> "duckduckgo".equals(name) || "brave".equals(name));

        LinkedHashSet<String> effective = new LinkedHashSet<>();
        if (legacyTwoProviderTimeChain) {
            appendCapable(effective, preferred, timeRange);
            effective.addAll(configured);
        } else {
            effective.addAll(configured);
            appendCapable(effective, preferred, timeRange);
        }

        for (Map.Entry<String, SearchProvider> entry : providers.entrySet()) {
            if (entry.getValue().supportsTimeRange(timeRange)) effective.add(entry.getKey());
        }
        return new ArrayList<>(effective);
    }

    private void appendCapable(LinkedHashSet<String> target, List<String> names, SearchTimeRange timeRange) {
        for (String providerName : names) {
            SearchProvider provider = providers.get(providerName);
            if (provider != null && provider.supportsTimeRange(timeRange)) target.add(providerName);
        }
    }

    private List<String> capableProviders(SearchTimeRange timeRange) {
        List<String> capable = new ArrayList<>();
        for (Map.Entry<String, SearchProvider> entry : providers.entrySet()) {
            if (entry.getValue().supportsTimeRange(timeRange)) capable.add(entry.getKey());
        }
        return capable;
    }

    private List<SearchItem> searchAuto(String query, int limit, String region, SearchTimeRange timeRange,
                                        List<String> providerOrder) {
        LinkedHashMap<String, SearchItem> merged = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        int attempted = 0;
        int skipped = 0;
        int capable = 0;

        if (providerOrder == null || providerOrder.isEmpty()) {
            throw new UpstreamException("No free search providers configured for the selected route");
        }

        for (String configuredName : providerOrder) {
            String providerName = normalizeProviderName(configuredName);
            if (providerName.isBlank()) {
                errors.add("<blank>: invalid provider name");
                upstreamLog.warn("[OPENREACH-SEARCH] provider_skip provider=<blank> reason=INVALID_PROVIDER_NAME");
                skipped++;
                continue;
            }
            SearchProvider provider = providers.get(providerName);
            if (provider == null) {
                errors.add(providerName + ": not registered");
                upstreamLog.warn("[OPENREACH-SEARCH] provider_skip provider={} reason=NOT_REGISTERED", providerName);
                skipped++;
                continue;
            }
            if (timeRange.isRestricted() && !provider.supportsTimeRange(timeRange)) {
                errors.add(provider.name() + ": skipped (timeRange unsupported)");
                upstreamLog.info("[OPENREACH-SEARCH] provider_skip provider={} reason=UNSUPPORTED_CAPABILITY capability=timeRange value={}",
                        provider.name(), timeRange.apiValue());
                skipped++;
                continue;
            }

            capable++;
            long cooldownRemainingMs = cooldownRemainingMs(provider.name());
            if (cooldownRemainingMs > 0) {
                errors.add(provider.name() + ": skipped (cooldown " + cooldownRemainingMs + "ms)");
                upstreamLog.info("[OPENREACH-SEARCH] provider_skip provider={} reason=COOLDOWN remainingMs={}",
                        provider.name(), cooldownRemainingMs);
                skipped++;
                continue;
            }

            attempted++;
            long providerStarted = System.nanoTime();
            upstreamLog.info("[OPENREACH-SEARCH] provider_start provider={} region={} timeRange={} limit={}",
                    provider.name(), region, timeRange.apiValue(), limit);
            try {
                List<SearchItem> result = provider.search(query, limit, region, timeRange);
                if (result == null || result.isEmpty()) {
                    errors.add(provider.name() + ": empty result");
                    upstreamLog.warn("[OPENREACH-SEARCH] provider_fail provider={} type=PARSE_EMPTY latencyMs={} message=empty_result",
                            provider.name(), elapsedMs(providerStarted));
                    continue;
                }
                for (SearchItem item : result) {
                    if (item == null) continue;
                    String key = canonicalUrl(item.url());
                    if (!key.isBlank()) merged.putIfAbsent(key, item);
                    if (merged.size() >= limit) break;
                }
                upstreamLog.info("[OPENREACH-SEARCH] provider_success provider={} returned={} merged={} latencyMs={}",
                        provider.name(), result.size(), merged.size(), elapsedMs(providerStarted));
                if (merged.size() >= limit) break;
            } catch (RuntimeException ex) {
                String message = compactMessage(ex);
                String failureType = UpstreamFailureClassifier.classify(ex);
                errors.add(provider.name() + ": " + message);
                maybeStartCooldown(provider.name(), failureType);
                upstreamLog.warn("[OPENREACH-SEARCH] provider_fail provider={} type={} latencyMs={} message={}",
                        provider.name(), failureType, elapsedMs(providerStarted), message);
            }
        }

        if (merged.isEmpty()) {
            if (capable == 0 && timeRange.isRestricted()) {
                throw new BadRequestException("No configured search provider supports timeRange=" + timeRange.apiValue());
            }
            throw new UpstreamException("All free search providers failed (attempted=" + attempted + ", skipped=" + skipped
                    + ", chain=" + providerOrder + "): " + String.join(" | ", errors));
        }
        return new ArrayList<>(merged.values());
    }

    private long cooldownRemainingMs(String providerName) {
        Long until = providerCooldownUntilMs.get(normalizeProviderName(providerName));
        if (until == null) return 0L;
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0) {
            providerCooldownUntilMs.remove(normalizeProviderName(providerName), until);
            return 0L;
        }
        return remaining;
    }

    private void maybeStartCooldown(String providerName, String failureType) {
        long durationMs = switch (failureType) {
            case "HTTP_429" -> properties.getSearch().getRateLimitCooldownMs();
            case "BOT_CHALLENGE" -> properties.getSearch().getBotChallengeCooldownMs();
            case "HTTP_403" -> properties.getSearch().getForbiddenCooldownMs();
            default -> 0L;
        };
        if (durationMs <= 0) return;
        long until = System.currentTimeMillis() + durationMs;
        providerCooldownUntilMs.merge(normalizeProviderName(providerName), until, Math::max);
        upstreamLog.info("[OPENREACH-SEARCH] provider_cooldown provider={} type={} durationMs={}",
                providerName, failureType, durationMs);
    }

    private List<SearchItem> searchOne(String providerName, String query, int limit, String region,
                                       SearchTimeRange timeRange) {
        SearchProvider provider = providers.get(providerName.toLowerCase(Locale.ROOT));
        if (provider == null) {
            throw new BadRequestException("Unsupported search provider: " + providerName
                    + ". Supported: auto," + String.join(",", providers.keySet()));
        }
        if (timeRange.isRestricted() && !provider.supportsTimeRange(timeRange)) {
            throw new BadRequestException("Search provider '" + provider.name() + "' does not support timeRange="
                    + timeRange.apiValue() + ". Use provider=auto or choose a provider that supports this range.");
        }

        long providerStarted = System.nanoTime();
        upstreamLog.info("[OPENREACH-SEARCH] provider_start provider={} explicit=true region={} timeRange={} limit={}",
                provider.name(), region, timeRange.apiValue(), limit);
        try {
            List<SearchItem> result = provider.search(query, limit, region, timeRange);
            if (result == null || result.isEmpty()) {
                throw new UpstreamException(provider.name() + " returned no parsable results");
            }
            upstreamLog.info("[OPENREACH-SEARCH] provider_success provider={} explicit=true returned={} latencyMs={}",
                    provider.name(), result.size(), elapsedMs(providerStarted));
            return result;
        } catch (RuntimeException ex) {
            upstreamLog.warn("[OPENREACH-SEARCH] provider_fail provider={} explicit=true type={} latencyMs={} message={}",
                    provider.name(), UpstreamFailureClassifier.classify(ex), elapsedMs(providerStarted), compactMessage(ex));
            throw ex;
        }
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

    private long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private String compactMessage(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return ex.getClass().getSimpleName();
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500);
    }
}
