package io.github.changlu.openreach.web;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.monitor.MonitorService;
import io.github.changlu.openreach.monitor.model.MonitorEndpointStat;
import io.github.changlu.openreach.monitor.model.MonitorOverview;
import io.github.changlu.openreach.monitor.model.MonitorRecordDetail;
import io.github.changlu.openreach.monitor.model.MonitorRecordPage;
import io.github.changlu.openreach.monitor.model.MonitorRecordQuery;
import io.github.changlu.openreach.monitor.model.MonitorTrendPoint;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/monitor")
public class MonitorController {
    private static final DateTimeFormatter EXPORT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final long MAX_RANGE_MS = Duration.ofDays(366).toMillis();
    private static final Set<String> ENDPOINTS = Set.of(
            "/api/web/search", "/api/web/image-search", "/api/web/read"
    );

    private final MonitorService monitorService;

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "available", monitorService.isAvailable(),
                "storage", monitorService.storageDescription(),
                "droppedEvents", monitorService.droppedEvents()
        );
    }

    @GetMapping("/overview")
    public MonitorOverview overview(@RequestParam long startTimeMs, @RequestParam long endTimeMs) {
        validateRange(startTimeMs, endTimeMs);
        return monitorService.overview(startTimeMs, endTimeMs);
    }

    @GetMapping("/trend")
    public List<MonitorTrendPoint> trend(
            @RequestParam long startTimeMs,
            @RequestParam long endTimeMs,
            @RequestParam(defaultValue = "day") String bucket,
            @RequestParam(defaultValue = "0") int timezoneOffsetMinutes) {
        validateRange(startTimeMs, endTimeMs);
        if (!"hour".equalsIgnoreCase(bucket) && !"day".equalsIgnoreCase(bucket)) {
            throw new BadRequestException("bucket must be hour or day");
        }
        if (timezoneOffsetMinutes < -840 || timezoneOffsetMinutes > 840) {
            throw new BadRequestException("timezoneOffsetMinutes out of range");
        }
        return monitorService.trend(startTimeMs, endTimeMs, bucket.toLowerCase(), timezoneOffsetMinutes);
    }

    @GetMapping("/distribution")
    public List<MonitorEndpointStat> distribution(@RequestParam long startTimeMs, @RequestParam long endTimeMs) {
        validateRange(startTimeMs, endTimeMs);
        return monitorService.distribution(startTimeMs, endTimeMs);
    }

    @GetMapping("/records")
    public MonitorRecordPage records(
            @RequestParam long startTimeMs,
            @RequestParam long endTimeMs,
            @RequestParam(name = "requestStartTimeMs", required = false) Long requestStartTimeMs,
            @RequestParam(name = "requestEndTimeMs", required = false) Long requestEndTimeMs,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "all") String endpoint,
            @RequestParam(defaultValue = "") String keyword) {
        return monitorService.records(recordQuery(
                startTimeMs, endTimeMs, requestStartTimeMs, requestEndTimeMs,
                page, pageSize, status, endpoint, keyword));
    }

    /**
     * Streams all failure records matching the same filters used by the request table.
     * The response is a UTF-8 diagnostic log attachment rather than the current page only.
     */
    @GetMapping(value = "/records/export", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<StreamingResponseBody> exportRecords(
            @RequestParam long startTimeMs,
            @RequestParam long endTimeMs,
            @RequestParam(name = "requestStartTimeMs", required = false) Long requestStartTimeMs,
            @RequestParam(name = "requestEndTimeMs", required = false) Long requestEndTimeMs,
            @RequestParam(defaultValue = "failure") String status,
            @RequestParam(defaultValue = "all") String endpoint,
            @RequestParam(defaultValue = "") String keyword) {
        if (!"failure".equals(status)) {
            throw new BadRequestException("failure export only supports status=failure");
        }
        MonitorRecordQuery query = recordQuery(
                startTimeMs, endTimeMs, requestStartTimeMs, requestEndTimeMs,
                1, 1, status, endpoint, keyword);
        long total = monitorService.records(query).total();
        String filename = "openreach-failed-requests-" + EXPORT_TIME.format(Instant.now()) + ".log";
        Instant exportedAt = Instant.now();

        StreamingResponseBody body = outputStream -> {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            // UTF-8 BOM helps common desktop editors recognize Chinese logs correctly.
            writer.write('\uFEFF');
            writeExportHeader(writer, query, total, exportedAt);
            final long[] index = {0L};
            long streamed = monitorService.exportRecords(query, record -> {
                index[0]++;
                writeFailureRecord(writer, record, index[0], total);
                // Periodic flush lets large exports begin downloading immediately.
                if (index[0] % 50 == 0) writer.flush();
            });
            writer.write("\n================================================================================\n");
            writer.write("导出完成\n");
            writer.write("实际导出失败请求数量: " + streamed + "\n");
            writer.flush();
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-OpenReach-Export-Count", Long.toString(total))
                .header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=UTF-8")
                .body(body);
    }

    @GetMapping("/records/{traceId}")
    public ResponseEntity<MonitorRecordDetail> detail(@PathVariable String traceId) {
        if (traceId == null || !traceId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new BadRequestException("invalid traceId");
        }
        return monitorService.detail(traceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private void writeExportHeader(BufferedWriter writer, MonitorRecordQuery query, long total, Instant exportedAt) throws IOException {
        writer.write("OpenReach 失败请求详细日志\n");
        writer.write("================================================================================\n");
        writer.write("导出时间(UTC): " + exportedAt + "\n");
        writer.write("筛选开始时间(UTC): " + Instant.ofEpochMilli(query.startTimeMs()) + "\n");
        writer.write("筛选结束时间(UTC): " + Instant.ofEpochMilli(query.endTimeMs()) + "\n");
        writer.write("请求状态: failure\n");
        writer.write("接口筛选: " + safeLine(query.endpoint()) + "\n");
        writer.write("关键词筛选: " + (query.keyword().isBlank() ? "(无)" : safeLine(query.keyword())) + "\n");
        writer.write("匹配失败请求数量: " + total + "\n");
        writer.write("说明: 每条记录均包含完整入参/返回值；若 Payload 已超过保留期则显示为空。\n");
        writer.write("================================================================================\n");
    }

    private void writeFailureRecord(BufferedWriter writer, MonitorRecordDetail record, long index, long total) throws IOException {
        writer.write("\n[失败请求 " + index + " / " + total + "]\n");
        writer.write("--------------------------------------------------------------------------------\n");
        writer.write("请求时间(UTC): " + Instant.ofEpochMilli(record.requestTimeMs()) + "\n");
        writer.write("请求时间戳(ms): " + record.requestTimeMs() + "\n");
        writer.write("Trace ID: " + safeLine(record.traceId()) + "\n");
        writer.write("客户端 IP: " + safeLine(record.clientIp()) + "\n");
        writer.write("请求接口: " + safeLine(record.method()) + " " + safeLine(record.endpoint()) + "\n");
        writer.write("HTTP 状态: " + record.httpStatus() + "\n");
        writer.write("请求耗时: " + record.latencyMs() + " ms\n");
        writer.write("Provider: " + safeLine(record.provider()) + "\n");
        writer.write("错误码: " + safeLine(record.errorCode()) + "\n");
        writer.write("错误原因: " + safeLine(record.errorMessage()) + "\n");
        writer.write("请求大小: " + record.requestBytes() + " bytes\n");
        writer.write("响应大小: " + record.responseBytes() + " bytes\n");
        writer.write("Payload 截断: " + (record.payloadTruncated() ? "是" : "否") + "\n");
        writer.write("\n----- 输入参数 BEGIN -----\n");
        writer.write(payloadText(record.requestPayload()));
        writer.write("\n----- 输入参数 END -----\n");
        writer.write("\n----- 输出参数 BEGIN -----\n");
        writer.write(payloadText(record.responsePayload()));
        writer.write("\n----- 输出参数 END -----\n");
        writer.write("--------------------------------------------------------------------------------\n");
    }

    private String payloadText(String payload) {
        if (payload == null || payload.isBlank()) return "(Payload 为空或已超过保留期)";
        return payload;
    }

    private String safeLine(String value) {
        if (value == null || value.isBlank()) return "-";
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private MonitorRecordQuery recordQuery(long startTimeMs, long endTimeMs,
                                           Long requestStartTimeMs, Long requestEndTimeMs,
                                           int page, int pageSize, String status, String endpoint, String keyword) {
        validateRange(startTimeMs, endTimeMs);
        long effectiveStartTimeMs = startTimeMs;
        long effectiveEndTimeMs = endTimeMs;
        if (requestStartTimeMs != null || requestEndTimeMs != null) {
            if (requestStartTimeMs == null || requestEndTimeMs == null) {
                throw new BadRequestException("requestStartTimeMs and requestEndTimeMs must be provided together");
            }
            validateRange(requestStartTimeMs, requestEndTimeMs);
            effectiveStartTimeMs = requestStartTimeMs;
            effectiveEndTimeMs = requestEndTimeMs;
        }
        if (page < 1) throw new BadRequestException("page must be >= 1");
        if (pageSize < 1 || pageSize > 100) throw new BadRequestException("pageSize must be between 1 and 100");
        if (!Set.of("all", "success", "failure").contains(status)) {
            throw new BadRequestException("status must be all, success or failure");
        }
        if (!"all".equals(endpoint) && !ENDPOINTS.contains(endpoint)) {
            throw new BadRequestException("unsupported endpoint filter");
        }
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedKeyword.length() > 200) throw new BadRequestException("keyword is too long");
        return new MonitorRecordQuery(effectiveStartTimeMs, effectiveEndTimeMs, page, pageSize, status, endpoint, normalizedKeyword);
    }

    private void validateRange(long startTimeMs, long endTimeMs) {
        if (startTimeMs < 0 || endTimeMs < 0 || startTimeMs > endTimeMs) {
            throw new BadRequestException("invalid monitor time range");
        }
        if (endTimeMs - startTimeMs > MAX_RANGE_MS) {
            throw new BadRequestException("monitor time range cannot exceed 366 days");
        }
    }
}
