package io.github.changlu.openreach.search.dto;

public record SearchItem(
        int rank,
        String title,
        String url,
        String snippet,
        String source
) {}
