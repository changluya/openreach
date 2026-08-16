package io.github.changlu.openreach.monitor.model;

import java.util.List;

public record MonitorRecordPage(
        List<MonitorRecordSummary> items,
        long total,
        int page,
        int pageSize,
        int totalPages
) {}
