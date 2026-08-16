package io.github.changlu.openreach.monitor.store;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.monitor.Utf8MojibakeRepair;
import io.github.changlu.openreach.monitor.model.MonitorEndpointStat;
import io.github.changlu.openreach.monitor.model.MonitorOverview;
import io.github.changlu.openreach.monitor.model.MonitorRecordDetail;
import io.github.changlu.openreach.monitor.model.MonitorRecordPage;
import io.github.changlu.openreach.monitor.model.MonitorRecordQuery;
import io.github.changlu.openreach.monitor.model.MonitorRecordSummary;
import io.github.changlu.openreach.monitor.model.MonitorRequestEvent;
import io.github.changlu.openreach.monitor.model.MonitorTrendPoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "openreach.web.monitor", name = "storage", havingValue = "sqlite", matchIfMissing = true)
public class SqliteMonitorRecordStore implements MonitorRecordStore {
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "split request metadata and payload storage", "db/monitor/sqlite/V001__init.sql"),
            new Migration(2, "repair legacy UTF-8 monitor payload encoding", null)
    );
    private static final int SCHEMA_VERSION = MIGRATIONS.get(MIGRATIONS.size() - 1).version();
    private static final int PREVIEW_CHARS = 512;
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final WebCapabilityProperties properties;
    private volatile String jdbcUrl;
    private volatile Path databasePath;

    public SqliteMonitorRecordStore(WebCapabilityProperties properties) {
        this.properties = properties;
    }

    @Override
    public synchronized void initialize() throws Exception {
        WebCapabilityProperties.Monitor monitor = properties.getMonitor();
        Path dir = Paths.get(monitor.getDataDir()).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        databasePath = dir.resolve(monitor.getSqliteFile()).normalize();
        if (!databasePath.startsWith(dir)) {
            throw new IllegalArgumentException("monitor sqlite file must stay under monitor data-dir");
        }
        jdbcUrl = "jdbc:sqlite:" + databasePath;

        try (Connection connection = openConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("PRAGMA wal_autocheckpoint=1000");
                statement.execute("PRAGMA journal_size_limit=67108864");
                statement.execute("CREATE TABLE IF NOT EXISTS monitor_schema_version (" +
                        "version INTEGER PRIMARY KEY, " +
                        "description TEXT NOT NULL, " +
                        "applied_at_ms INTEGER NOT NULL)");
            }
            migrate(connection);
        }
    }

    private void migrate(Connection connection) throws SQLException {
        quickCheck(connection);
        int current = currentVersion(connection);
        if (current > SCHEMA_VERSION) {
            throw new SQLException("monitor database schema version " + current + " is newer than supported " + SCHEMA_VERSION);
        }
        if (current > 0 && current < SCHEMA_VERSION) {
            backupBeforeMigration(connection, current, SCHEMA_VERSION);
        }
        for (Migration migration : MIGRATIONS) {
            if (migration.version() > current) {
                applyMigration(connection, migration);
                current = migration.version();
            }
        }
        if (current != SCHEMA_VERSION) {
            throw new SQLException("monitor schema migration incomplete: current=" + current + ", expected=" + SCHEMA_VERSION);
        }
        quickCheck(connection);
    }

    private void quickCheck(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("PRAGMA quick_check")) {
            if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                throw new SQLException("SQLite quick_check failed for monitor database");
            }
        }
    }

    private void backupBeforeMigration(Connection connection, int fromVersion, int toVersion) throws SQLException {
        try {
            Path backupDir = databasePath.getParent().resolve("backup");
            Files.createDirectories(backupDir);
            String filename = "openreach-monitor-before-schema-V" + toVersion + "-from-V" + fromVersion + "-" + BACKUP_TIME.format(Instant.now()) + ".db";
            Path backup = backupDir.resolve(filename).toAbsolutePath().normalize();
            String escaped = backup.toString().replace("'", "''");
            try (Statement statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + escaped + "'");
            }
            pruneBackups(backupDir);
        } catch (IOException ex) {
            throw new SQLException("cannot create monitor migration backup", ex);
        }
    }

    private void pruneBackups(Path backupDir) throws IOException {
        int keep = Math.max(1, properties.getMonitor().getBackupRetentionCount());
        try (var stream = Files.list(backupDir)) {
            List<Path> backups = stream
                    .filter(path -> path.getFileName().toString().startsWith("openreach-monitor-before-schema-"))
                    .filter(path -> path.getFileName().toString().endsWith(".db"))
                    .sorted(Comparator.comparingLong(this::lastModifiedSafe).reversed())
                    .toList();
            for (int i = keep; i < backups.size(); i++) Files.deleteIfExists(backups.get(i));
        }
    }

    private long lastModifiedSafe(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException ignored) { return 0L; }
    }

    private int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM monitor_schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void applyMigration(Connection connection, Migration migration) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (migration.resourcePath() != null) {
                executeMigrationResource(connection, migration.resourcePath());
            }
            if (migration.version() == 2) {
                repairLegacyPayloadEncoding(connection);
            }
            try (PreparedStatement version = connection.prepareStatement(
                    "INSERT INTO monitor_schema_version(version, description, applied_at_ms) VALUES (?, ?, ?)")) {
                version.setInt(1, migration.version());
                version.setString(2, migration.description());
                version.setLong(3, System.currentTimeMillis());
                version.executeUpdate();
            }
            connection.commit();
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }


    private void repairLegacyPayloadEncoding(Connection connection) throws SQLException {
        List<PayloadRepair> repairs = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT record_id, request_payload, response_payload FROM monitor_request_payload");
             ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                long recordId = rs.getLong(1);
                String requestPayload = rs.getString(2);
                String responsePayload = rs.getString(3);
                String repairedRequest = Utf8MojibakeRepair.repairIfNeeded(requestPayload);
                String repairedResponse = Utf8MojibakeRepair.repairIfNeeded(responsePayload);
                if (!java.util.Objects.equals(requestPayload, repairedRequest)
                        || !java.util.Objects.equals(responsePayload, repairedResponse)) {
                    repairs.add(new PayloadRepair(recordId, repairedRequest, repairedResponse));
                }
            }
        }
        if (repairs.isEmpty()) return;

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE monitor_request_payload SET request_payload=?, response_payload=? WHERE record_id=?")) {
            for (PayloadRepair repair : repairs) {
                update.setString(1, repair.requestPayload());
                update.setString(2, repair.responsePayload());
                update.setLong(3, repair.recordId());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private void executeMigrationResource(Connection connection, String resourcePath) throws SQLException {
        String sql;
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            try (InputStream input = resource.getInputStream()) {
                sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            throw new SQLException("cannot read monitor migration: " + resourcePath, ex);
        }
        for (String statementSql : sql.split(";")) {
            String statementText = statementSql.trim();
            if (statementText.isEmpty()) continue;
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementText);
            }
        }
    }

    @Override
    public void saveBatch(List<MonitorRequestEvent> events) throws Exception {
        if (events == null || events.isEmpty()) return;
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insertRecord = connection.prepareStatement(
                    "INSERT OR IGNORE INTO monitor_request_record(" +
                            "trace_id, request_time_ms, client_ip, method, endpoint, http_status, success, latency_ms, " +
                            "provider, error_code, error_message, payload_truncated, created_at_ms) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                 PreparedStatement selectId = connection.prepareStatement(
                         "SELECT id FROM monitor_request_record WHERE trace_id = ?");
                 PreparedStatement upsertPayload = connection.prepareStatement(
                         "INSERT INTO monitor_request_payload(record_id, request_payload, response_payload, request_bytes, response_bytes, created_at_ms) " +
                                 "VALUES (?, ?, ?, ?, ?, ?) " +
                                 "ON CONFLICT(record_id) DO UPDATE SET " +
                                 "request_payload=excluded.request_payload, response_payload=excluded.response_payload, " +
                                 "request_bytes=excluded.request_bytes, response_bytes=excluded.response_bytes, created_at_ms=excluded.created_at_ms")) {
                for (MonitorRequestEvent event : events) {
                    bindRecord(insertRecord, event);
                    insertRecord.executeUpdate();

                    selectId.setString(1, event.traceId());
                    try (ResultSet rs = selectId.executeQuery()) {
                        if (!rs.next()) continue;
                        long recordId = rs.getLong(1);
                        upsertPayload.setLong(1, recordId);
                        upsertPayload.setString(2, event.requestPayload());
                        upsertPayload.setString(3, event.responsePayload());
                        upsertPayload.setLong(4, event.requestBytes());
                        upsertPayload.setLong(5, event.responseBytes());
                        upsertPayload.setLong(6, event.createdAtMs());
                        upsertPayload.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    private void bindRecord(PreparedStatement ps, MonitorRequestEvent event) throws SQLException {
        ps.setString(1, event.traceId());
        ps.setLong(2, event.requestTimeMs());
        ps.setString(3, event.clientIp());
        ps.setString(4, event.method());
        ps.setString(5, event.endpoint());
        ps.setInt(6, event.httpStatus());
        ps.setInt(7, event.success() ? 1 : 0);
        ps.setLong(8, event.latencyMs());
        ps.setString(9, event.provider());
        ps.setString(10, event.errorCode());
        ps.setString(11, event.errorMessage());
        ps.setInt(12, event.payloadTruncated() ? 1 : 0);
        ps.setLong(13, event.createdAtMs());
    }

    @Override
    public MonitorOverview overview(long startTimeMs, long endTimeMs, long droppedEvents) throws Exception {
        long total = 0;
        long success = 0;
        long uniqueIpCount = 0;
        long avgLatency = 0;
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT COUNT(*), COALESCE(SUM(success),0), " +
                             "COUNT(DISTINCT NULLIF(TRIM(client_ip), '')), COALESCE(AVG(latency_ms),0) " +
                             "FROM monitor_request_record WHERE request_time_ms >= ? AND request_time_ms <= ?")) {
            ps.setLong(1, startTimeMs);
            ps.setLong(2, endTimeMs);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    total = rs.getLong(1);
                    success = rs.getLong(2);
                    uniqueIpCount = rs.getLong(3);
                    avgLatency = Math.round(rs.getDouble(4));
                }
            }
        }
        long failure = total - success;
        long p95 = percentile95(startTimeMs, endTimeMs, total);
        double successRate = total == 0 ? 0.0 : Math.round((success * 10000.0 / total)) / 100.0;
        return new MonitorOverview(total, success, failure, uniqueIpCount, successRate, avgLatency, p95, droppedEvents);
    }

    private long percentile95(long startTimeMs, long endTimeMs, long total) throws Exception {
        if (total <= 0) return 0;
        long offset = Math.max(0, (long) Math.ceil(total * 0.95) - 1);
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT latency_ms FROM monitor_request_record " +
                             "WHERE request_time_ms >= ? AND request_time_ms <= ? " +
                             "ORDER BY latency_ms ASC LIMIT 1 OFFSET ?")) {
            ps.setLong(1, startTimeMs);
            ps.setLong(2, endTimeMs);
            ps.setLong(3, offset);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    @Override
    public List<MonitorTrendPoint> trend(long startTimeMs, long endTimeMs, String bucket, int timezoneOffsetMinutes) throws Exception {
        long bucketMs = "hour".equalsIgnoreCase(bucket) ? 3_600_000L : 86_400_000L;
        // JS getTimezoneOffset() is UTC-local; converting local timestamps therefore subtracts this value.
        long localShiftMs = -timezoneOffsetMinutes * 60_000L;
        String sql = "SELECT ((request_time_ms + ?) / ?) AS bucket_id, " +
                "COALESCE(SUM(success),0), COALESCE(SUM(CASE WHEN success=0 THEN 1 ELSE 0 END),0) " +
                "FROM monitor_request_record WHERE request_time_ms >= ? AND request_time_ms <= ? " +
                "GROUP BY bucket_id ORDER BY bucket_id";
        List<MonitorTrendPoint> result = new ArrayList<>();
        try (Connection connection = openConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, localShiftMs);
            ps.setLong(2, bucketMs);
            ps.setLong(3, startTimeMs);
            ps.setLong(4, endTimeMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long bucketId = rs.getLong(1);
                    long bucketStartMs = bucketId * bucketMs - localShiftMs;
                    result.add(new MonitorTrendPoint(bucketStartMs, rs.getLong(2), rs.getLong(3)));
                }
            }
        }
        return result;
    }

    @Override
    public List<MonitorEndpointStat> distribution(long startTimeMs, long endTimeMs) throws Exception {
        List<MonitorEndpointStat> result = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT endpoint, COUNT(*), COALESCE(SUM(success),0), " +
                             "COALESCE(SUM(CASE WHEN success=0 THEN 1 ELSE 0 END),0) " +
                             "FROM monitor_request_record WHERE request_time_ms >= ? AND request_time_ms <= ? " +
                             "GROUP BY endpoint ORDER BY COUNT(*) DESC")) {
            ps.setLong(1, startTimeMs);
            ps.setLong(2, endTimeMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new MonitorEndpointStat(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
                }
            }
        }
        return result;
    }

    @Override
    public MonitorRecordPage query(MonitorRecordQuery query) throws Exception {
        SqlFilter filter = buildFilter(query);
        long total;
        try (Connection connection = openConnection();
             PreparedStatement count = connection.prepareStatement(
                     "SELECT COUNT(*) FROM monitor_request_record r LEFT JOIN monitor_request_payload p ON p.record_id=r.id " + filter.where)) {
            bindFilter(count, filter.params);
            try (ResultSet rs = count.executeQuery()) {
                total = rs.next() ? rs.getLong(1) : 0;
            }
        }

        List<MonitorRecordSummary> items = new ArrayList<>();
        String sql = "SELECT r.id,r.trace_id,r.request_time_ms,r.client_ip,r.method,r.endpoint," +
                "substr(COALESCE(p.request_payload,''),1," + PREVIEW_CHARS + ")," +
                "substr(COALESCE(p.response_payload,''),1," + PREVIEW_CHARS + ")," +
                "r.http_status,r.success,r.latency_ms,r.provider,r.error_code,r.error_message,r.payload_truncated " +
                "FROM monitor_request_record r LEFT JOIN monitor_request_payload p ON p.record_id=r.id " +
                filter.where + " ORDER BY r.request_time_ms DESC LIMIT ? OFFSET ?";
        try (Connection connection = openConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = bindFilter(ps, filter.params);
            ps.setInt(index++, query.pageSize());
            ps.setLong(index, (long) (query.page() - 1) * query.pageSize());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new MonitorRecordSummary(
                            rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getString(8), rs.getInt(9), rs.getInt(10) == 1, rs.getLong(11),
                            rs.getString(12), rs.getString(13), rs.getString(14), rs.getInt(15) == 1));
                }
            }
        }
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) query.pageSize());
        return new MonitorRecordPage(items, total, query.page(), query.pageSize(), totalPages);
    }

    private SqlFilter buildFilter(MonitorRecordQuery query) {
        StringBuilder where = new StringBuilder("WHERE r.request_time_ms >= ? AND r.request_time_ms <= ?");
        List<Object> params = new ArrayList<>();
        params.add(query.startTimeMs());
        params.add(query.endTimeMs());
        String status = normalized(query.status());
        if ("success".equals(status)) {
            where.append(" AND r.success=1");
        } else if ("failure".equals(status)) {
            where.append(" AND r.success=0");
        }
        String endpoint = normalized(query.endpoint());
        if (!endpoint.isBlank() && !"all".equals(endpoint)) {
            where.append(" AND r.endpoint=?");
            params.add(query.endpoint());
        }
        String keyword = normalized(query.keyword());
        if (!keyword.isBlank()) {
            where.append(" AND (LOWER(COALESCE(r.client_ip,'')) LIKE ? OR LOWER(r.trace_id) LIKE ? OR LOWER(r.endpoint) LIKE ? " +
                    "OR LOWER(COALESCE(p.request_payload,'')) LIKE ? OR LOWER(COALESCE(p.response_payload,'')) LIKE ?)");
            String like = "%" + keyword + "%";
            for (int i = 0; i < 5; i++) params.add(like);
        }
        return new SqlFilter(where.toString(), params);
    }

    private int bindFilter(PreparedStatement ps, List<Object> params) throws SQLException {
        int index = 1;
        for (Object param : params) {
            if (param instanceof Long value) ps.setLong(index++, value);
            else if (param instanceof Integer value) ps.setInt(index++, value);
            else ps.setString(index++, String.valueOf(param));
        }
        return index;
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public Optional<MonitorRecordDetail> findByTraceId(String traceId) throws Exception {
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT r.id,r.trace_id,r.request_time_ms,r.client_ip,r.method,r.endpoint," +
                             "p.request_payload,p.response_payload,r.http_status,r.success,r.latency_ms,r.provider," +
                             "r.error_code,r.error_message,COALESCE(p.request_bytes,0),COALESCE(p.response_bytes,0)," +
                             "r.payload_truncated,r.created_at_ms " +
                             "FROM monitor_request_record r LEFT JOIN monitor_request_payload p ON p.record_id=r.id WHERE r.trace_id=?")) {
            ps.setString(1, traceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readDetail(rs));
            }
        }
    }

    @Override
    public long streamDetails(MonitorRecordQuery query, MonitorRecordSink sink) throws Exception {
        if (sink == null) throw new IllegalArgumentException("monitor export sink is required");
        SqlFilter filter = buildFilter(query);
        String sql = "SELECT r.id,r.trace_id,r.request_time_ms,r.client_ip,r.method,r.endpoint," +
                "p.request_payload,p.response_payload,r.http_status,r.success,r.latency_ms,r.provider," +
                "r.error_code,r.error_message,COALESCE(p.request_bytes,0),COALESCE(p.response_bytes,0)," +
                "r.payload_truncated,r.created_at_ms " +
                "FROM monitor_request_record r LEFT JOIN monitor_request_payload p ON p.record_id=r.id " +
                filter.where + " ORDER BY r.request_time_ms DESC";
        long count = 0;
        try (Connection connection = openConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            bindFilter(ps, filter.params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sink.accept(readDetail(rs));
                    count++;
                }
            }
        }
        return count;
    }

    private MonitorRecordDetail readDetail(ResultSet rs) throws SQLException {
        return new MonitorRecordDetail(
                rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getInt(9), rs.getInt(10) == 1, rs.getLong(11), rs.getString(12),
                rs.getString(13), rs.getString(14), rs.getLong(15), rs.getLong(16), rs.getInt(17) == 1, rs.getLong(18));
    }

    @Override
    public int deletePayloadBefore(long cutoffTimeMs) throws Exception {
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "DELETE FROM monitor_request_payload WHERE record_id IN " +
                             "(SELECT id FROM monitor_request_record WHERE request_time_ms < ?)") ) {
            ps.setLong(1, cutoffTimeMs);
            return ps.executeUpdate();
        }
    }

    @Override
    public int deleteRecordsBefore(long cutoffTimeMs) throws Exception {
        try (Connection connection = openConnection();
             PreparedStatement ps = connection.prepareStatement("DELETE FROM monitor_request_record WHERE request_time_ms < ?")) {
            ps.setLong(1, cutoffTimeMs);
            return ps.executeUpdate();
        }
    }

    @Override
    public String storageDescription() {
        return databasePath == null ? "sqlite:not-initialized" : "sqlite:" + databasePath;
    }

    private Connection openConnection() throws SQLException {
        if (jdbcUrl == null) throw new SQLException("monitor sqlite store is not initialized");
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=" + Math.max(1000, properties.getMonitor().getSqliteBusyTimeoutMs()));
        }
        return connection;
    }

    private record Migration(int version, String description, String resourcePath) {}

    private record PayloadRepair(long recordId, String requestPayload, String responsePayload) {}

    private record SqlFilter(String where, List<Object> params) {}
}
