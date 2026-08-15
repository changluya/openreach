package io.github.changlu.openreach.imagesearch;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchItem;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchRequest;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchResponse;
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

    public ImageSearchService(List<ImageSearchProvider> providers, WebCapabilityProperties properties) {
        for (ImageSearchProvider provider : providers) {
            this.providers.put(provider.name().toLowerCase(Locale.ROOT), provider);
        }
        this.properties = properties;
    }

    public ImageSearchResponse search(ImageSearchRequest request) {
        long started = System.nanoTime();
        int limit = request.effectiveLimit(properties.getImageSearch().getMaxResults());
        String region = request.effectiveRegion();
        String selected = request.effectiveProvider(properties.getImageSearch().getProvider());

        List<ImageSearchItem> items = "auto".equals(selected)
                ? searchAuto(request.query(), limit, region)
                : searchOne(selected, request.query(), limit, region);

        long latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        return new ImageSearchResponse(selected, request.query(), region, items.size(), latencyMs, renumber(items, limit));
    }

    private List<ImageSearchItem> searchAuto(String query, int limit, String region) {
        LinkedHashMap<String, ImageSearchItem> merged = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (String providerName : properties.getImageSearch().getProviderOrder()) {
            ImageSearchProvider provider = providers.get(providerName.toLowerCase(Locale.ROOT));
            if (provider == null) {
                errors.add(providerName + ": not registered");
                continue;
            }
            try {
                List<ImageSearchItem> result = provider.search(query, limit, region);
                if (result == null) continue;
                for (ImageSearchItem item : result) {
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
            throw new UpstreamException("All free image search providers failed: " + String.join(" | ", errors));
        }
        return new ArrayList<>(merged.values());
    }

    private List<ImageSearchItem> searchOne(String providerName, String query, int limit, String region) {
        ImageSearchProvider provider = providers.get(providerName.toLowerCase(Locale.ROOT));
        if (provider == null) {
            throw new BadRequestException("Unsupported image search provider: " + providerName
                    + ". Supported: auto," + String.join(",", providers.keySet()));
        }
        List<ImageSearchItem> result = provider.search(query, limit, region);
        if (result == null || result.isEmpty()) {
            throw new UpstreamException(provider.name() + " returned no parsable image results");
        }
        return result;
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

    private String compactMessage(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return ex.getClass().getSimpleName();
        return message.replaceAll("\\s+", " ").trim();
    }
}
