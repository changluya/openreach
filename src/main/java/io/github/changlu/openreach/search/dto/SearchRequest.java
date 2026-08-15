package io.github.changlu.openreach.search.dto;

import io.github.changlu.openreach.search.SearchTimeRange;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record SearchRequest(
        @NotBlank @Size(max = 500) String query,
        @Min(1) @Max(20) Integer limit,
        @Size(max = 32) String region,
        @Size(max = 32) String provider,
        @Size(max = 32) String timeRange
) {
    public int effectiveLimit(int configuredMax) {
        int requested = limit == null ? 10 : limit;
        return Math.min(requested, configuredMax);
    }

    public String effectiveRegion() {
        return region == null || region.isBlank() ? "auto" : region.trim();
    }

    public String effectiveProvider(String configuredDefault) {
        String value = provider == null || provider.isBlank() ? configuredDefault : provider;
        return value == null || value.isBlank() ? "auto" : value.trim().toLowerCase(Locale.ROOT);
    }

    public SearchTimeRange effectiveTimeRange() {
        return SearchTimeRange.parse(timeRange);
    }
}
