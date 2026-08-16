package io.github.changlu.openreach.observability;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.monitor.PayloadRedactor;
import io.github.changlu.openreach.monitor.model.MonitorRequestEvent;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RequestTraceFilterTest {
    @Test
    void createsUniqueTimestampedTraceAndPublishesCapturedRequest() throws Exception {
        WebCapabilityProperties props = new WebCapabilityProperties();
        AtomicReference<MonitorRequestEvent> captured = new AtomicReference<>();
        RequestTraceFilter filter = new RequestTraceFilter(captured::set, props, new PayloadRedactor(), JsonMapper.builder().build());
        AtomicReference<String> inChain = new AtomicReference<>();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/web/search");
        req.setContentType("application/json");
        req.setContent("{\"query\":\"OpenReach\",\"token\":\"secret-value\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (request, response) -> {
            // ContentCachingRequestWrapper caches bytes as the downstream MVC layer consumes them.
            // Simulate a real @RequestBody controller before asserting the captured monitor payload.
            request.getInputStream().readAllBytes();
            inChain.set(TraceContext.traceId());
            response.setContentType("application/json");
            response.getWriter().write("{\"provider\":\"bing\",\"count\":1}");
        });

        String trace = res.getHeader(RequestTraceFilter.TRACE_HEADER);
        assertEquals(inChain.get(), trace);
        assertTrue(trace.matches("req-\\d{8}T\\d{9}-[0-9a-f]{8}"));
        assertNotEquals(trace, RequestTraceFilter.newTraceId());
        assertEquals("{\"provider\":\"bing\",\"count\":1}", res.getContentAsString());

        MonitorRequestEvent event = captured.get();
        assertNotNull(event);
        assertEquals(trace, event.traceId());
        assertEquals("/api/web/search", event.endpoint());
        assertEquals("bing", event.provider());
        assertTrue(event.requestPayload().contains("***REDACTED***"));
        assertFalse(event.requestPayload().contains("secret-value"));
    }


    @Test
    void capturesUtf8JsonResponseWithoutBeingFooledByServletIso88591Default() throws Exception {
        WebCapabilityProperties props = new WebCapabilityProperties();
        AtomicReference<MonitorRequestEvent> captured = new AtomicReference<>();
        RequestTraceFilter filter = new RequestTraceFilter(captured::set, props, new PayloadRedactor(), JsonMapper.builder().build());
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/web/search");
        req.setContentType("application/json");
        req.setContent("{\"query\":\"大模型 AI Agent 最新发布 2026年8月\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (request, response) -> {
            // A real Spring MVC @RequestBody handler consumes the request stream; doing the same here
            // makes this filter-level test exercise the production caching lifecycle.
            request.getInputStream().readAllBytes();
            response.setContentType("application/json");
            String json = "{\"provider\":\"baidu\",\"title\":\"AI应用周度观察（2026年8月）\"}";
            response.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        });

        MonitorRequestEvent event = captured.get();
        assertNotNull(event);
        assertTrue(event.requestPayload().contains("大模型 AI Agent 最新发布 2026年8月"));
        assertTrue(event.responsePayload().contains("AI应用周度观察（2026年8月）"));
        assertFalse(event.responsePayload().contains("åºç¨"));
        assertArrayEquals(
                "{\"provider\":\"baidu\",\"title\":\"AI应用周度观察（2026年8月）\"}".getBytes(StandardCharsets.UTF_8),
                res.getContentAsByteArray());
    }

    @Test
    void respectsExplicitResponseCharsetWhenPresent() throws Exception {
        WebCapabilityProperties props = new WebCapabilityProperties();
        AtomicReference<MonitorRequestEvent> captured = new AtomicReference<>();
        RequestTraceFilter filter = new RequestTraceFilter(captured::set, props, new PayloadRedactor(), JsonMapper.builder().build());
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/web/search");
        req.setContentType("application/json");
        req.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (request, response) -> {
            response.setContentType("application/json;charset=GB18030");
            String json = "{\"provider\":\"baidu\",\"title\":\"中文结果\"}";
            response.getOutputStream().write(json.getBytes(java.nio.charset.Charset.forName("GB18030")));
        });

        assertEquals("baidu", captured.get().provider());
        assertTrue(captured.get().responsePayload().contains("中文结果"));
    }

    @Test
    void doesNotMonitorInternalMonitorApi() throws Exception {
        WebCapabilityProperties props = new WebCapabilityProperties();
        AtomicReference<MonitorRequestEvent> captured = new AtomicReference<>();
        RequestTraceFilter filter = new RequestTraceFilter(captured::set, props, new PayloadRedactor(), JsonMapper.builder().build());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/monitor/overview");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (request, response) -> response.getWriter().write("{}"));
        assertNull(captured.get());
        assertNull(res.getHeader(RequestTraceFilter.TRACE_HEADER));
    }
}
