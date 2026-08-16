package io.github.changlu.openreach.observability;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RequestTraceFilterTest {
    @Test
    void createsUniqueTimestampedTraceAndReturnsHeader() throws Exception {
        RequestTraceFilter filter = new RequestTraceFilter();
        AtomicReference<String> inChain = new AtomicReference<>();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/web/search");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (request, response) -> inChain.set(TraceContext.traceId()));
        String trace = res.getHeader(RequestTraceFilter.TRACE_HEADER);
        assertEquals(inChain.get(), trace);
        assertTrue(trace.matches("req-\\d{8}T\\d{9}-[0-9a-f]{8}"));
        assertNotEquals(trace, RequestTraceFilter.newTraceId());
    }
}
