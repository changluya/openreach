package io.github.changlu.openreach.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {
    public static final String TRACE_HEADER = "X-OpenReach-Trace-Id";
    private static final Logger apiLog = LoggerFactory.getLogger("OPENREACH.API");
    private static final DateTimeFormatter TRACE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmssSSS")
            .withZone(ZoneOffset.UTC);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = newTraceId();
        String api = apiName(request.getRequestURI());
        long started = System.nanoTime();

        MDC.put(TraceContext.TRACE_ID, traceId);
        MDC.put(TraceContext.API, api);
        response.setHeader(TRACE_HEADER, traceId);
        apiLog.info("[OPENREACH-API] request_start method={} path={} contentLength={}",
                request.getMethod(), safePath(request.getRequestURI()), request.getContentLengthLong());
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException ex) {
            apiLog.error("[OPENREACH-API] request_exception type={} message={}",
                    ex.getClass().getSimpleName(), compact(ex.getMessage()));
            throw ex;
        } finally {
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            apiLog.info("[OPENREACH-API] request_end status={} latencyMs={}", response.getStatus(), latencyMs);
            MDC.remove(TraceContext.API);
            MDC.remove(TraceContext.TRACE_ID);
        }
    }


    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/");
    }

    static String newTraceId() {
        return "req-" + TRACE_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String apiName(String path) {
        if (path == null) return "unknown";
        return switch (path) {
            case "/api/web/search" -> "search";
            case "/api/web/image-search" -> "image-search";
            case "/api/web/read" -> "read";
            default -> path.startsWith("/api/") ? "api-other" : "static";
        };
    }

    private String safePath(String path) {
        if (path == null || path.isBlank()) return "/";
        return path.replaceAll("[\\r\\n\\t]", "_");
    }

    private String compact(String message) {
        if (message == null || message.isBlank()) return "-";
        String value = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() <= 300 ? value : value.substring(0, 300);
    }
}
