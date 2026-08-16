package io.github.changlu.openreach.monitor.model;

public record MonitorEndpointStat(
        String endpoint,
        long total,
        long success,
        long failure
) {}
