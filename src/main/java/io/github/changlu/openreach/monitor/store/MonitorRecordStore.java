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
 * Persistence boundary for the monitor subsystem.
 *
 * <p>Business code depends only on this interface. SQLite is the default v0.1.3
 * implementation; future MySQL/PostgreSQL implementations can be selected by
 * configuration without changing request capture, service or controller code.</p>
 */
public interface MonitorRecordStore {
    void initialize() throws Exception;

    void saveBatch(List<MonitorRequestEvent> events) throws Exception;

    MonitorOverview overview(long startTimeMs, long endTimeMs, long droppedEvents) throws Exception;

    List<MonitorTrendPoint> trend(long startTimeMs, long endTimeMs, String bucket, int timezoneOffsetMinutes) throws Exception;

    List<MonitorEndpointStat> distribution(long startTimeMs, long endTimeMs) throws Exception;

    MonitorRecordPage query(MonitorRecordQuery query) throws Exception;

    Optional<MonitorRecordDetail> findByTraceId(String traceId) throws Exception;

    long streamDetails(MonitorRecordQuery query, MonitorRecordSink sink) throws Exception;

    int deletePayloadBefore(long cutoffTimeMs) throws Exception;

    int deleteRecordsBefore(long cutoffTimeMs) throws Exception;

    String storageDescription();
}
