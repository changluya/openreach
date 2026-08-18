package io.github.changlu.openreach;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.monitor.MonitorService;
import io.github.changlu.openreach.monitor.model.MonitorRecordDetail;
import io.github.changlu.openreach.monitor.model.MonitorRecordPage;
import io.github.changlu.openreach.monitor.model.MonitorRecordQuery;
import io.github.changlu.openreach.monitor.store.MonitorRecordSink;
import io.github.changlu.openreach.monitor.store.MonitorRecordStore;
import io.github.changlu.openreach.web.MonitorController;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorControllerTest {

    @Test
    void recordsKeepsLegacyDashboardRangeWhenDedicatedRequestRangeIsAbsent() {
        CapturingMonitorService service = new CapturingMonitorService();
        MonitorController controller = new MonitorController(service);

        controller.records(1000L, 9000L, null, null, 1, 20, "all", "all", "");

        MonitorRecordQuery query = service.lastQuery;
        assertNotNull(query);
        assertEquals(1000L, query.startTimeMs());
        assertEquals(9000L, query.endTimeMs());
    }

    @Test
    void recordsUsesDedicatedRequestTimeRangeWhenProvided() {
        CapturingMonitorService service = new CapturingMonitorService();
        MonitorController controller = new MonitorController(service);

        controller.records(1000L, 9000L, 2500L, 4500L, 2, 50, "failure", "/api/web/search", " trace ");

        MonitorRecordQuery query = service.lastQuery;
        assertNotNull(query);
        assertEquals(2500L, query.startTimeMs());
        assertEquals(4500L, query.endTimeMs());
        assertEquals(2, query.page());
        assertEquals(50, query.pageSize());
        assertEquals("failure", query.status());
        assertEquals("/api/web/search", query.endpoint());
        assertEquals("trace", query.keyword());
    }

    @Test
    void recordsRejectsPartialDedicatedRequestTimeRange() {
        MonitorController controller = new MonitorController(new CapturingMonitorService());

        BadRequestException onlyStart = assertThrows(BadRequestException.class,
                () -> controller.records(1000L, 9000L, 2500L, null, 1, 20, "all", "all", ""));
        assertTrue(onlyStart.getMessage().contains("must be provided together"));

        BadRequestException onlyEnd = assertThrows(BadRequestException.class,
                () -> controller.records(1000L, 9000L, null, 4500L, 1, 20, "all", "all", ""));
        assertTrue(onlyEnd.getMessage().contains("must be provided together"));
    }

    @Test
    void recordsRejectsInvalidDedicatedRequestTimeRange() {
        MonitorController controller = new MonitorController(new CapturingMonitorService());

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> controller.records(1000L, 9000L, 5000L, 4000L, 1, 20, "all", "all", ""));
        assertEquals("invalid monitor time range", error.getMessage());
    }

    @Test
    void requestTimeParametersAreOptionalOnRecordsAndExportEndpoints() throws Exception {
        assertOptionalRequestTimeParameters(MonitorController.class.getMethod(
                "records", long.class, long.class, Long.class, Long.class,
                int.class, int.class, String.class, String.class, String.class));
        assertOptionalRequestTimeParameters(MonitorController.class.getMethod(
                "exportRecords", long.class, long.class, Long.class, Long.class,
                String.class, String.class, String.class));
    }

    @Test
    void failureExportStreamsUtf8DiagnosticLogWithFullPayloadsAndDedicatedRequestRange() throws Exception {
        long now = Instant.parse("2026-08-16T08:30:00Z").toEpochMilli();
        long requestStart = Instant.parse("2026-08-16T08:00:00Z").toEpochMilli();
        long requestEnd = Instant.parse("2026-08-16T08:20:00Z").toEpochMilli();
        MonitorRecordDetail first = new MonitorRecordDetail(
                1L, "trace-failure-1", now - 1000, "10.0.0.8", "POST", "/api/web/search",
                "{\"query\":\"大模型 AI Agent\"}",
                "{\"code\":\"UPSTREAM_ERROR\",\"message\":\"百度上游失败\"}",
                502, false, 1266L, "baidu", "UPSTREAM_ERROR", "百度上游失败",
                35L, 68L, false, now - 900
        );
        MonitorRecordDetail second = new MonitorRecordDetail(
                2L, "trace-failure-2", now, "10.0.0.9", "POST", "/api/web/read",
                "{\"url\":\"https://example.com\"}",
                "{\"code\":\"UPSTREAM_TIMEOUT\"}",
                504, false, 4200L, null, "UPSTREAM_TIMEOUT", "upstream timeout",
                37L, 31L, false, now
        );
        CapturingMonitorService service = new CapturingMonitorService();
        service.page = new MonitorRecordPage(List.of(), 2L, 1, 1, 2);
        service.exportRecords = List.of(first, second);

        MonitorController controller = new MonitorController(service);
        ResponseEntity<StreamingResponseBody> response = controller.exportRecords(
                now - 86_400_000L, now + 1L,
                requestStart, requestEnd,
                "failure", "all", ""
        );

        assertNotNull(service.lastQuery);
        assertEquals(requestStart, service.lastQuery.startTimeMs());
        assertEquals(requestEnd, service.lastQuery.endTimeMs());
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getFirst("Content-Disposition").endsWith(".log\""));
        assertEquals("2", response.getHeaders().getFirst("X-OpenReach-Export-Count"));
        assertTrue(response.getHeaders().getFirst("Content-Type").contains("text/plain"));

        StreamingResponseBody body = response.getBody();
        assertNotNull(body);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        body.writeTo(output);
        String log = new String(output.toByteArray(), StandardCharsets.UTF_8);

        assertTrue(log.contains("OpenReach 失败请求详细日志"));
        assertTrue(log.contains("筛选开始时间(UTC): " + Instant.ofEpochMilli(requestStart)));
        assertTrue(log.contains("筛选结束时间(UTC): " + Instant.ofEpochMilli(requestEnd)));
        assertTrue(log.contains("匹配失败请求数量: 2"));
        assertTrue(log.contains("trace-failure-1"));
        assertTrue(log.contains("trace-failure-2"));
        assertTrue(log.contains("大模型 AI Agent"));
        assertTrue(log.contains("百度上游失败"));
        assertTrue(log.contains("----- 输入参数 BEGIN -----"));
        assertTrue(log.contains("----- 输出参数 BEGIN -----"));
        assertTrue(log.contains("实际导出失败请求数量: 2"));
    }

    private static void assertOptionalRequestTimeParameters(Method method) {
        List<RequestParam> requestTimeParameters = Arrays.stream(method.getParameters())
                .map(parameter -> parameter.getAnnotation(RequestParam.class))
                .filter(annotation -> annotation != null
                        && (annotation.name().equals("requestStartTimeMs") || annotation.name().equals("requestEndTimeMs")))
                .toList();
        assertEquals(2, requestTimeParameters.size());
        for (RequestParam annotation : requestTimeParameters) {
            assertFalse(annotation.required());
        }
    }

    private static final class CapturingMonitorService extends MonitorService {
        private MonitorRecordQuery lastQuery;
        private MonitorRecordPage page = new MonitorRecordPage(List.of(), 0L, 1, 20, 0);
        private List<MonitorRecordDetail> exportRecords = List.of();

        private CapturingMonitorService() {
            super(noopStore(), new WebCapabilityProperties());
        }

        @Override
        public MonitorRecordPage records(MonitorRecordQuery query) {
            this.lastQuery = query;
            return page;
        }

        @Override
        public long exportRecords(MonitorRecordQuery query, MonitorRecordSink sink) {
            this.lastQuery = query;
            try {
                for (MonitorRecordDetail record : exportRecords) sink.accept(record);
                return exportRecords.size();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    private static MonitorRecordStore noopStore() {
        return (MonitorRecordStore) Proxy.newProxyInstance(
                MonitorRecordStore.class.getClassLoader(),
                new Class<?>[]{MonitorRecordStore.class},
                (proxy, method, args) -> {
                    if (method.getReturnType() == String.class) return "noop";
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == boolean.class) return false;
                    return null;
                }
        );
    }
}
