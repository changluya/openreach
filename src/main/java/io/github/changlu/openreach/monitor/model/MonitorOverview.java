package io.github.changlu.openreach.monitor.model;

public record MonitorOverview(
        long total,
        long success,
        long failure,
        long uniqueIpCount,
        double successRate,
        long avgLatencyMs,
        long p95LatencyMs,
        long droppedEvents
) {}
