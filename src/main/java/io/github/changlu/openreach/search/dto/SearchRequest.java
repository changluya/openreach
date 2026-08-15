package io.github.changlu.openreach.search.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record SearchRequest(
        @NotBlank String query,
        @Min(1) @Max(20) Integer limit,
        String region,
        String provider
) {
    public int effectiveLimit(int configuredMax) {
        int requested = limit == null ? 10 : limit;
        return Math.min(requested, configuredMax);
    }

    public String effectiveRegion() {
        return region == null || region.isBlank() ? "CN" : region.trim();
    }

    public String effectiveProvider(String configuredDefault) {
        String value = provider == null || provider.isBlank() ? configuredDefault : provider;
        return value == null || value.isBlank() ? "auto" : value.trim().toLowerCase();
    }
}
