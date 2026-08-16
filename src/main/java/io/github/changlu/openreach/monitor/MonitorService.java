package io.github.changlu.openreach.monitor;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.monitor.model.MonitorEndpointStat;
import io.github.changlu.openreach.monitor.model.MonitorOverview;
import io.github.changlu.openreach.monitor.model.MonitorRecordDetail;
import io.github.changlu.openreach.monitor.model.MonitorRecordPage;
import io.github.changlu.openreach.monitor.model.MonitorRecordQuery;
import io.github.changlu.openreach.monitor.model.MonitorRequestEvent;
import io.github.changlu.openreach.monitor.model.MonitorTrendPoint;
import io.github.changlu.openreach.monitor.store.MonitorRecordSink;
import io.github.changlu.openreach.monitor.store.MonitorRecordStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MonitorService implements MonitorEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final MonitorRecordStore store;
    private final WebCapabilityProperties.Monitor config;
    private final BlockingQueue<MonitorRequestEvent> queue;
    private final AtomicLong droppedEvents = new AtomicLong();

    private volatile boolean running;
    private volatile boolean available;
    private volatile String unavailableReason = "not initialized";
    private Thread writerThread;
    private long nextCleanupAtMs;

    public MonitorService(MonitorRecordStore store, WebCapabilityProperties properties) {
        this.store = store;
        this.config = properties.getMonitor();
        this.queue = new ArrayBlockingQueue<>(Math.max(100, config.getQueueCapacity()));
    }

    @PostConstruct
    public void start() {
        if (!config.isPersistenceEnabled()) {
            unavailableReason = "monitor persistence disabled";
            log.warn("OpenReach monitor persistence is disabled by configuration");
            return;
        }
        try {
            store.initialize();
            available = true;
            running = true;
            nextCleanupAtMs = System.currentTimeMillis();
            writerThread = new Thread(this::writerLoop, "openreach-monitor-writer");
            writerThread.setDaemon(true);
            writerThread.start();
            log.info("OpenReach monitor storage ready: {}", store.storageDescription());
        } catch (Exception ex) {
            unavailableReason = ex.getClass().getSimpleName() + ": " + safeMessage(ex);
            log.error("OpenReach monitor storage initialization failed; public Web APIs will continue without persistence", ex);
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        Thread thread = writerThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1500L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        flushRemaining();
    }

    @Override
    public void publish(MonitorRequestEvent event) {
        if (event == null || !available || !running) return;
        if (!queue.offer(event)) {
            long dropped = droppedEvents.incrementAndGet();
            if (dropped == 1 || dropped % 100 == 0) {
                log.warn("OpenReach monitor queue is full; droppedEvents={}", dropped);
            }
        }
    }

    public boolean isAvailable() { return available; }
    public String storageDescription() { return available ? store.storageDescription() : unavailableReason; }
    public long droppedEvents() { return droppedEvents.get(); }

    public MonitorOverview overview(long startTimeMs, long endTimeMs) {
        return call(() -> store.overview(startTimeMs, endTimeMs, droppedEvents.get()));
    }

    public List<MonitorTrendPoint> trend(long startTimeMs, long endTimeMs, String bucket, int timezoneOffsetMinutes) {
        return call(() -> store.trend(startTimeMs, endTimeMs, bucket, timezoneOffsetMinutes));
    }

    public List<MonitorEndpointStat> distribution(long startTimeMs, long endTimeMs) {
        return call(() -> store.distribution(startTimeMs, endTimeMs));
    }

    public MonitorRecordPage records(MonitorRecordQuery query) {
        return call(() -> store.query(query));
    }

    public Optional<MonitorRecordDetail> detail(String traceId) {
        return call(() -> store.findByTraceId(traceId));
    }

    public long exportRecords(MonitorRecordQuery query, MonitorRecordSink sink) {
        return call(() -> store.streamDetails(query, sink));
    }

    private void writerLoop() {
        List<MonitorRequestEvent> batch = new ArrayList<>(Math.max(1, config.getBatchSize()));
        long flushIntervalMs = Math.max(50L, config.getFlushIntervalMs());
        int batchSize = Math.max(1, config.getBatchSize());
        while (running || !queue.isEmpty()) {
            try {
                MonitorRequestEvent first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first != null) batch.add(first);
                queue.drainTo(batch, Math.max(0, batchSize - batch.size()));
                if (!batch.isEmpty()) {
                    store.saveBatch(batch);
                    batch.clear();
                }
                cleanupIfDue();
            } catch (InterruptedException ex) {
                if (!running) break;
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                long lost = batch.size();
                if (lost > 0) droppedEvents.addAndGet(lost);
                log.error("OpenReach monitor background write failed; droppedBatch={} public Web APIs are unaffected", lost, ex);
                batch.clear();
            }
        }
    }

    private void cleanupIfDue() {
        long now = System.currentTimeMillis();
        if (now < nextCleanupAtMs) return;
        nextCleanupAtMs = now + Duration.ofMinutes(Math.max(1, config.getCleanupIntervalMinutes())).toMillis();
        try {
            long payloadCutoff = now - Duration.ofDays(Math.max(1, config.getPayloadRetentionDays())).toMillis();
            long metadataCutoff = now - Duration.ofDays(Math.max(1, config.getMetadataRetentionDays())).toMillis();
            int payloadDeleted = store.deletePayloadBefore(payloadCutoff);
            int recordsDeleted = store.deleteRecordsBefore(metadataCutoff);
            if (payloadDeleted > 0 || recordsDeleted > 0) {
                log.info("OpenReach monitor retention cleanup payloadDeleted={} recordsDeleted={}", payloadDeleted, recordsDeleted);
            }
        } catch (Exception ex) {
            log.warn("OpenReach monitor retention cleanup failed: {}", safeMessage(ex));
        }
    }

    private void flushRemaining() {
        if (!available) return;
        List<MonitorRequestEvent> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (remaining.isEmpty()) return;
        try {
            int batchSize = Math.max(1, config.getBatchSize());
            for (int from = 0; from < remaining.size(); from += batchSize) {
                store.saveBatch(remaining.subList(from, Math.min(remaining.size(), from + batchSize)));
            }
        } catch (Exception ex) {
            droppedEvents.addAndGet(remaining.size());
            log.warn("OpenReach monitor shutdown flush failed; dropped={} reason={}", remaining.size(), safeMessage(ex));
        }
    }

    private <T> T call(StoreCall<T> call) {
        ensureAvailable();
        try {
            return call.call();
        } catch (MonitorStorageUnavailableException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MonitorStorageUnavailableException("Monitor storage query failed", ex);
        }
    }

    private void ensureAvailable() {
        if (!available) {
            throw new MonitorStorageUnavailableException("Monitor storage unavailable: " + unavailableReason);
        }
    }

    private String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return ex.getClass().getSimpleName();
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    @FunctionalInterface
    private interface StoreCall<T> { T call() throws Exception; }
}
