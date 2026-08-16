package io.github.changlu.openreach.monitor.model;

public record MonitorTrendPoint(
        long bucketStartMs,
        long success,
        long failure
) {
    public long total() { return success + failure; }
}
