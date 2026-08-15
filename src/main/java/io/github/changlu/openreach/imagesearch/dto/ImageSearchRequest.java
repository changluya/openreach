package io.github.changlu.openreach.imagesearch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ImageSearchRequest(
        @NotBlank String query,
        @Min(1) @Max(30) Integer limit,
        String region,
        String provider
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
        return value == null || value.isBlank() ? "auto" : value.trim().toLowerCase();
    }
}
