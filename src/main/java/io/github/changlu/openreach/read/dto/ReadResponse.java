package io.github.changlu.openreach.read.dto;

import java.util.List;
import java.util.Map;

public record ReadResponse(
        String url,
        String finalUrl,
        String title,
        String content,
        String contentType,
        String reader,
        boolean truncated,
        long latencyMs,
        Map<String, String> metadata,
        List<String> links
) {}
