package io.github.changlu.openreach.monitor.model;

public record MonitorRecordQuery(
        long startTimeMs,
        long endTimeMs,
        int page,
        int pageSize,
        String status,
        String endpoint,
        String keyword
) {}
