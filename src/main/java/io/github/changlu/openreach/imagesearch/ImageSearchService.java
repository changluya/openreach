package io.github.changlu.openreach.imagesearch;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchItem;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchRequest;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchResponse;
import io.github.changlu.openreach.imagesearch.validation.ImageDownloadVerifier;
import io.github.changlu.openreach.routing.ProviderChainResolver;
import io.github.changlu.openreach.routing.SearchRoute;
import io.github.changlu.openreach.routing.SearchRouteResolver;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ImageSearchService {
    private final Map<String, ImageSearchProvider> providers = new LinkedHashMap<>();
    private final WebCapabilityProperties properties;
    private final SearchRouteResolver routeResolver;
    private final ProviderChainResolver chainResolver;
    private final ImageDownloadVerifier downloadVerifier;

    public ImageSearchService(List<ImageSearchProvider> providers, WebCapabilityProperties properties,
                              SearchRouteResolver routeResolver, ProviderChainResolver chainResolver,
                              ImageDownloadVerifier downloadVerifier) {
        for (ImageSearchProvider provider : providers) {
            this.providers.put(provider.name().toLowerCase(Locale.ROOT), provider);
        }
        this.properties = properties;
        this.routeResolver = routeResolver;
        this.chainResolver = chainResolver;
        this.downloadVerifier = downloadVerifier;
    }

    public ImageSearchResponse search(ImageSearchRequest request) {
        long started = System.nanoTime();
        int limit = request.effectiveLimit(properties.getImageSearch().getMaxResults());
        int candidateLimit = candidateLimit(limit);
        String region = request.effectiveRegion();
        String selected = request.effectiveProvider(properties.getImageSearch().getProvider());

        SearchRoute route = routeResolver.resolve(region);
        List<ImageSearchItem> items = "auto".equals(selected)
                ? searchAuto(request.query(), limit, candidateLimit, region, chainResolver.imageSearchProviders(route))
                : searchOne(selected, request.query(), limit, candidateLimit, region);

        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        return new ImageSearchResponse(selected, request.query(), region, items.size(), latencyMs, renumber(items, limit));
    }

    private List<ImageSearchItem> searchAuto(String query, int limit, int candidateLimit, String region,
                                              List<String> providerOrder) {
        LinkedHashMap<String, ImageSearchItem> merged = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        if (providerOrder == null || providerOrder.isEmpty()) {
            throw new UpstreamException("No free image search providers configured for the selected route");
        }

        for (String configuredName : providerOrder) {
            String providerName = normalizeProviderName(configuredName);
            if (providerName.isBlank()) {
                errors.add("<blank>: invalid provider name");
                continue;
            }
            ImageSearchProvider provider = providers.get(providerName);
            if (provider == null) {
                errors.add(providerName + ": not registered");
                continue;
            }
            try {
                List<ImageSearchItem> candidates = provider.search(query, candidateLimit, region);
                if (candidates == null || candidates.isEmpty()) {
                    errors.add(provider.name() + ": empty result");
                    continue;
                }
                List<ImageSearchItem> downloadable = downloadVerifier.filterDownloadable(candidates, limit - merged.size());
                if (downloadable.isEmpty()) {
                    errors.add(provider.name() + ": no downloadable images");
                    continue;
                }
                for (ImageSearchItem item : downloadable) {
                    String key = normalize(item.imageUrl());
                    if (!key.isBlank()) merged.putIfAbsent(key, item);
                    if (merged.size() >= limit) break;
                }
                if (merged.size() >= limit) break;
            } catch (RuntimeException ex) {
                errors.add(provider.name() + ": " + compactMessage(ex));
            }
        }

        if (merged.isEmpty()) {
            throw new UpstreamException("All free image search providers failed or returned no downloadable images: "
                    + String.join(" | ", errors));
        }
        return new ArrayList<>(merged.values());
    }

    private List<ImageSearchItem> searchOne(String providerName, String query, int limit, int candidateLimit,
                                             String region) {
        ImageSearchProvider provider = providers.get(providerName.toLowerCase(Locale.ROOT));
        if (provider == null) {
            throw new BadRequestException("Unsupported image search provider: " + providerName
                    + ". Supported: auto," + String.join(",", providers.keySet()));
        }
        List<ImageSearchItem> candidates = provider.search(query, candidateLimit, region);
        if (candidates == null || candidates.isEmpty()) {
            throw new UpstreamException(provider.name() + " returned no parsable image results");
        }
        List<ImageSearchItem> downloadable = downloadVerifier.filterDownloadable(candidates, limit);
        if (downloadable.isEmpty()) {
            throw new UpstreamException(provider.name() + " returned image results, but none are directly downloadable now");
        }
        return downloadable;
    }

    int candidateLimit(int requestedLimit) {
        int multiplier = Math.max(1, properties.getImageSearch().getDownloadCandidateMultiplier());
        long multiplied = (long) requestedLimit * multiplier;
        int maxCandidates = Math.max(requestedLimit, properties.getImageSearch().getDownloadMaxCandidates());
        return (int) Math.min(multiplied, maxCandidates);
    }

    private List<ImageSearchItem> renumber(List<ImageSearchItem> items, int limit) {
        List<ImageSearchItem> result = new ArrayList<>();
        for (ImageSearchItem item : items) {
            if (result.size() >= limit) break;
            result.add(new ImageSearchItem(
                    result.size() + 1,
                    item.title(), item.imageUrl(), item.thumbnailUrl(), item.sourcePageUrl(),
                    item.provider(), item.source(), item.domain(), item.width(), item.height(), item.imageFormat(),
                    item.license(), item.licenseUrl()
            ));
        }
        return result;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
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
