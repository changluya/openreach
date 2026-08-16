package io.github.changlu.openreach.monitor.model;

public record MonitorRecordSummary(
        long id,
        String traceId,
        long requestTimeMs,
        String clientIp,
        String method,
        String endpoint,
        String requestPreview,
        String responsePreview,
        int httpStatus,
        boolean success,
        long latencyMs,
        String provider,
        String errorCode,
        String errorMessage,
        boolean payloadTruncated
) {}
