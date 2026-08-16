package io.github.changlu.openreach.monitor.model;

public record MonitorRecordDetail(
        long id,
        String traceId,
        long requestTimeMs,
        String clientIp,
        String method,
        String endpoint,
        String requestPayload,
        String responsePayload,
        int httpStatus,
        boolean success,
        long latencyMs,
        String provider,
        String errorCode,
        String errorMessage,
        long requestBytes,
        long responseBytes,
        boolean payloadTruncated,
        long createdAtMs
) {}
