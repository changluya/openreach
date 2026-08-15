package io.github.changlu.openreach.search.dto;

import java.util.List;

public record SearchResponse(
        String provider,
        String query,
        String region,
        int count,
        long latencyMs,
        List<SearchItem> items
) {}
