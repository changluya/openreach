package io.github.changlu.openreach.imagesearch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record ImageSearchRequest(
        @NotBlank @Size(max = 500) String query,
        @Min(1) @Max(30) Integer limit,
        @Size(max = 32) String region,
        @Size(max = 32) String provider
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
}
