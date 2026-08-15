package io.github.changlu.openreach.imagesearch.dto;

import java.util.List;

public record ImageSearchResponse(
        String provider,
        String query,
        String region,
        int count,
        long latencyMs,
        List<ImageSearchItem> items
) {}
