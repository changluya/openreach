package io.github.changlu.openreach.monitor.store;

import io.github.changlu.openreach.monitor.model.MonitorEndpointStat;
import io.github.changlu.openreach.monitor.model.MonitorOverview;
import io.github.changlu.openreach.monitor.model.MonitorRecordDetail;
import io.github.changlu.openreach.monitor.model.MonitorRecordPage;
import io.github.changlu.openreach.monitor.model.MonitorRecordQuery;
import io.github.changlu.openreach.monitor.model.MonitorRequestEvent;
import io.github.changlu.openreach.monitor.model.MonitorTrendPoint;

import java.util.List;
import java.util.Optional;

/**
 * Fallback store used when a configured storage engine has no implementation in the current build.
 * MonitorService will degrade the monitor subsystem while keeping Search/Image Search/Read available.
 */
public final class UnavailableMonitorRecordStore implements MonitorRecordStore {
    private final String storage;

    public UnavailableMonitorRecordStore(String storage) {
        this.storage = storage == null || storage.isBlank() ? "unknown" : storage.trim();
    }

    @Override
    public void initialize() {
        throw new IllegalStateException("Unsupported monitor storage engine in this build: " + storage);
    }

    @Override public void saveBatch(List<MonitorRequestEvent> events) { throw unavailable(); }
    @Override public MonitorOverview overview(long startTimeMs, long endTimeMs, long droppedEvents) { throw unavailable(); }
    @Override public List<MonitorTrendPoint> trend(long startTimeMs, long endTimeMs, String bucket, int timezoneOffsetMinutes) { throw unavailable(); }
    @Override public List<MonitorEndpointStat> distribution(long startTimeMs, long endTimeMs) { throw unavailable(); }
    @Override public MonitorRecordPage query(MonitorRecordQuery query) { throw unavailable(); }
    @Override public Optional<MonitorRecordDetail> findByTraceId(String traceId) { throw unavailable(); }
    @Override public long streamDetails(MonitorRecordQuery query, MonitorRecordSink sink) { throw unavailable(); }
    @Override public int deletePayloadBefore(long cutoffTimeMs) { throw unavailable(); }
    @Override public int deleteRecordsBefore(long cutoffTimeMs) { throw unavailable(); }

    @Override
    public String storageDescription() {
        return storage + ":unsupported";
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException("Unsupported monitor storage engine in this build: " + storage);
    }
}
