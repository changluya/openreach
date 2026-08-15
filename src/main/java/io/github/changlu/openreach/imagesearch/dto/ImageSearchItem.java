package io.github.changlu.openreach.imagesearch.dto;

public record ImageSearchItem(
        int rank,
        String title,
        String imageUrl,
        String thumbnailUrl,
        String sourcePageUrl,
        String provider,
        String source,
        String domain,
        Integer width,
        Integer height,
        String imageFormat,
        String license,
        String licenseUrl
) {}
