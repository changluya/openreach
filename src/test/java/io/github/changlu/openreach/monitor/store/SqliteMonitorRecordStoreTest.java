package io.github.changlu.openreach.monitor.store;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.monitor.model.MonitorRecordQuery;
import io.github.changlu.openreach.monitor.model.MonitorRequestEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqliteMonitorRecordStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsSplitMetadataPayloadAndSupportsDashboardQueries() throws Exception {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getMonitor().setDataDir(tempDir.toString());
        props.getMonitor().setSqliteFile("monitor-test.db");
        SqliteMonitorRecordStore store = new SqliteMonitorRecordStore(props);
        store.initialize();

        long now = System.currentTimeMillis();
        store.saveBatch(List.of(
                event("trace-ok", now - 1000, "/api/web/search", 200, true, 120, "bing", null, null),
                event("trace-fail", now, "/api/web/read", 502, false, 880, null, "UPSTREAM_ERROR", "upstream failed")
        ));

        var overview = store.overview(now - 10_000, now + 10_000, 3);
        assertEquals(2, overview.total());
        assertEquals(1, overview.success());
        assertEquals(1, overview.failure());
        assertEquals(1, overview.uniqueIpCount());
        assertEquals(50.0, overview.successRate());
        assertEquals(3, overview.droppedEvents());
        assertTrue(overview.p95LatencyMs() >= 120);

        var distribution = store.distribution(now - 10_000, now + 10_000);
        assertEquals(2, distribution.size());

        var page = store.query(new MonitorRecordQuery(now - 10_000, now + 10_000, 1, 20, "failure", "all", ""));
        assertEquals(1, page.total());
        assertEquals("trace-fail", page.items().get(0).traceId());
        assertTrue(page.items().get(0).requestPreview().contains("query"));

        var detail = store.findByTraceId("trace-fail").orElseThrow();
        assertTrue(detail.requestPayload().contains("OpenReach"));
        assertTrue(detail.responsePayload().contains("UPSTREAM_ERROR"));
        assertEquals("upstream failed", detail.errorMessage());

        assertFalse(store.trend(now - 10_000, now + 10_000, "hour", 0).isEmpty());
    }

    @Test
    void overviewCountsDistinctNonBlankClientIps() throws Exception {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getMonitor().setDataDir(tempDir.toString());
        props.getMonitor().setSqliteFile("unique-ip-test.db");
        SqliteMonitorRecordStore store = new SqliteMonitorRecordStore(props);
        store.initialize();

        long now = System.currentTimeMillis();
        store.saveBatch(List.of(
                eventWithIp("trace-ip-1", now - 2000, "10.0.0.1"),
                eventWithIp("trace-ip-2", now - 1000, "10.0.0.1"),
                eventWithIp("trace-ip-3", now - 500, "10.0.0.2"),
                eventWithIp("trace-ip-blank", now, "")
        ));

        var overview = store.overview(now - 10_000, now + 10_000, 0);
        assertEquals(4, overview.total());
        assertEquals(2, overview.uniqueIpCount());
    }


    @Test
    void schemaV2RepairsLegacyUtf8PayloadsAfterBackup() throws Exception {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getMonitor().setDataDir(tempDir.toString());
        props.getMonitor().setSqliteFile("encoding-migration-test.db");
        SqliteMonitorRecordStore store = new SqliteMonitorRecordStore(props);
        store.initialize();

        long now = System.currentTimeMillis();
        String correctResponse = "{\"provider\":\"baidu\",\"title\":\"AI应用周度观察（2026年8月）\"}";
        String mojibakeResponse = new String(correctResponse.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        store.saveBatch(List.of(new MonitorRequestEvent(
                "trace-legacy-encoding", now, "127.0.0.1", "POST", "/api/web/search",
                "{\"query\":\"大模型\"}", mojibakeResponse,
                200, true, 100, "baidu", null, null,
                30, mojibakeResponse.length(), false, now
        )));

        Path database = tempDir.resolve("encoding-migration-test.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM monitor_schema_version WHERE version=2");
        }

        SqliteMonitorRecordStore upgraded = new SqliteMonitorRecordStore(props);
        upgraded.initialize();
        var detail = upgraded.findByTraceId("trace-legacy-encoding").orElseThrow();
        assertEquals(correctResponse, detail.responsePayload());

        Path backupDir = tempDir.resolve("backup");
        assertTrue(Files.isDirectory(backupDir));
        try (var stream = Files.list(backupDir)) {
            assertTrue(stream.anyMatch(path -> path.getFileName().toString().contains("before-schema-V2-from-V1")));
        }
    }

    @Test
    void streamsAllFilteredFailureDetailsForExportWithoutPagination() throws Exception {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getMonitor().setDataDir(tempDir.toString());
        props.getMonitor().setSqliteFile("export-test.db");
        SqliteMonitorRecordStore store = new SqliteMonitorRecordStore(props);
        store.initialize();

        long now = System.currentTimeMillis();
        store.saveBatch(List.of(
                event("trace-export-ok", now - 2000, "/api/web/search", 200, true, 120, "bing", null, null),
                event("trace-export-fail-1", now - 1000, "/api/web/search", 502, false, 880, "baidu", "UPSTREAM_ERROR", "first failure"),
                event("trace-export-fail-2", now, "/api/web/read", 504, false, 1200, null, "UPSTREAM_TIMEOUT", "second failure")
        ));

        List<io.github.changlu.openreach.monitor.model.MonitorRecordDetail> exported = new ArrayList<>();
        long count = store.streamDetails(
                new MonitorRecordQuery(now - 10_000, now + 10_000, 1, 1, "failure", "all", ""),
                exported::add);

        assertEquals(2, count);
        assertEquals(2, exported.size());
        assertEquals("trace-export-fail-2", exported.get(0).traceId());
        assertTrue(exported.stream().allMatch(item -> !item.success()));
        assertTrue(exported.stream().allMatch(item -> item.requestPayload().contains("OpenReach")));
        assertTrue(exported.stream().anyMatch(item -> item.responsePayload().contains("UPSTREAM_ERROR")));
        assertTrue(exported.stream().anyMatch(item -> item.responsePayload().contains("UPSTREAM_TIMEOUT")));
    }

    @Test
    void cleanupCanExpirePayloadBeforeMetadata() throws Exception {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getMonitor().setDataDir(tempDir.toString());
        props.getMonitor().setSqliteFile("retention-test.db");
        SqliteMonitorRecordStore store = new SqliteMonitorRecordStore(props);
        store.initialize();

        long old = System.currentTimeMillis() - 10_000;
        store.saveBatch(List.of(event("trace-old", old, "/api/web/search", 200, true, 100, "bing", null, null)));
        assertEquals(1, store.deletePayloadBefore(old + 1));
        var detail = store.findByTraceId("trace-old").orElseThrow();
        assertNull(detail.requestPayload());
        assertNull(detail.responsePayload());
        assertEquals(1, store.deleteRecordsBefore(old + 1));
        assertTrue(store.findByTraceId("trace-old").isEmpty());
    }

    private MonitorRequestEvent eventWithIp(String traceId, long time, String clientIp) {
        return new MonitorRequestEvent(
                traceId, time, clientIp, "POST", "/api/web/search",
                "{\"query\":\"OpenReach\"}", "{\"provider\":\"bing\",\"count\":1}",
                200, true, 100, "bing", null, null,
                21, 29, false, time
        );
    }

    private MonitorRequestEvent event(String traceId, long time, String endpoint, int status, boolean success,
                                      long latency, String provider, String errorCode, String errorMessage) {
        String response = success
                ? "{\"provider\":\"" + provider + "\",\"count\":1}"
                : "{\"code\":\"" + errorCode + "\",\"message\":\"" + errorMessage + "\"}";
        return new MonitorRequestEvent(
                traceId, time, "127.0.0.1", "POST", endpoint,
                "{\"query\":\"OpenReach\"}", response,
                status, success, latency, provider, errorCode, errorMessage,
                21, response.length(), false, time
        );
    }
}
