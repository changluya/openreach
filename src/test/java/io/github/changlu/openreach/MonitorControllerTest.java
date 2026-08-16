package io.github.changlu.openreach;

import io.github.changlu.openreach.monitor.MonitorService;
import io.github.changlu.openreach.monitor.model.MonitorRecordDetail;
import io.github.changlu.openreach.monitor.model.MonitorRecordPage;
import io.github.changlu.openreach.monitor.store.MonitorRecordSink;
import io.github.changlu.openreach.web.MonitorController;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonitorControllerTest {

    @Test
    void failureExportStreamsUtf8DiagnosticLogWithFullPayloads() throws Exception {
        MonitorService service = mock(MonitorService.class);
        long now = Instant.parse("2026-08-16T08:30:00Z").toEpochMilli();
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

        when(service.records(any())).thenReturn(new MonitorRecordPage(List.of(), 2L, 1, 1, 2));
        when(service.exportRecords(any(), any())).thenAnswer(invocation -> {
            MonitorRecordSink sink = invocation.getArgument(1);
            sink.accept(first);
            sink.accept(second);
            return 2L;
        });

        MonitorController controller = new MonitorController(service);
        ResponseEntity<StreamingResponseBody> response = controller.exportRecords(
                now - 86_400_000L, now + 1L, "failure", "all", ""
        );

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
        assertTrue(log.contains("匹配失败请求数量: 2"));
        assertTrue(log.contains("trace-failure-1"));
        assertTrue(log.contains("trace-failure-2"));
        assertTrue(log.contains("大模型 AI Agent"));
        assertTrue(log.contains("百度上游失败"));
        assertTrue(log.contains("----- 输入参数 BEGIN -----"));
        assertTrue(log.contains("----- 输出参数 BEGIN -----"));
        assertTrue(log.contains("实际导出失败请求数量: 2"));
    }
}
