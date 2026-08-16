package io.github.changlu.openreach.monitor.store;

import io.github.changlu.openreach.monitor.model.MonitorRecordDetail;

/**
 * Streaming sink used by monitor exports.
 *
 * <p>The store owns database iteration while the caller owns serialization/output.
 * This keeps export logic storage-engine agnostic and avoids loading all payloads
 * into JVM memory before a download starts.</p>
 */
@FunctionalInterface
public interface MonitorRecordSink {
    void accept(MonitorRecordDetail record) throws Exception;
}
