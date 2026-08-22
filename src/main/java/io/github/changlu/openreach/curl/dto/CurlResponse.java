package io.github.changlu.openreach.curl.dto;

import java.util.List;
import java.util.Map;

public record CurlResponse(
        String url,
        String finalUrl,
        String method,
        int statusCode,
        String contentType,
        String body,
        boolean truncated,
        int redirects,
        long latencyMs,
        Map<String, List<String>> headers
) {}
