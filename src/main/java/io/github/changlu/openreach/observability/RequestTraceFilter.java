package io.github.changlu.openreach.observability;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.monitor.MonitorEventPublisher;
import io.github.changlu.openreach.monitor.PayloadRedactor;
import io.github.changlu.openreach.monitor.model.MonitorRequestEvent;
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
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {
    public static final String TRACE_HEADER = "X-OpenReach-Trace-Id";
    private static final Logger apiLog = LoggerFactory.getLogger("OPENREACH.API");
    private static final Set<String> MONITORED_API_PATHS = Set.of(
            "/api/web/search", "/api/web/image-search", "/api/web/read", "/api/web/curl"
    );
    private static final Pattern CHARSET_PATTERN = Pattern.compile("charset\\s*=\\s*[\\\"']?([^;\\s\\\"']+)", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter TRACE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmssSSS")
            .withZone(ZoneOffset.UTC);

    private final MonitorEventPublisher monitorPublisher;
    private final WebCapabilityProperties properties;
    private final PayloadRedactor payloadRedactor;
    private final JsonMapper jsonMapper;

    public RequestTraceFilter(MonitorEventPublisher monitorPublisher,
                              WebCapabilityProperties properties,
                              PayloadRedactor payloadRedactor,
                              JsonMapper jsonMapper) {
        this.monitorPublisher = monitorPublisher;
        this.properties = properties;
        this.payloadRedactor = payloadRedactor;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = newTraceId();
        String api = apiName(request.getRequestURI());
        long requestTimeMs = System.currentTimeMillis();
        long started = System.nanoTime();
        int captureLimit = Math.max(1024, properties.getMonitor().getMaxPayloadBytes());
        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request, captureLimit);
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);

        MDC.put(TraceContext.TRACE_ID, traceId);
        MDC.put(TraceContext.API, api);
        responseWrapper.setHeader(TRACE_HEADER, traceId);
        apiLog.info("[OPENREACH-API] request_start method={} path={} contentLength={}",
                request.getMethod(), safePath(request.getRequestURI()), request.getContentLengthLong());
        try {
            filterChain.doFilter(requestWrapper, responseWrapper);
        } catch (ServletException | IOException | RuntimeException ex) {
            apiLog.error("[OPENREACH-API] request_exception type={} message={}",
                    ex.getClass().getSimpleName(), compact(ex.getMessage()));
            throw ex;
        } finally {
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            int status = responseWrapper.getStatus();
            byte[] responseBody = responseWrapper.getContentAsByteArray();
            try {
                publishMonitorEvent(requestWrapper, responseWrapper, traceId, requestTimeMs, latencyMs, responseBody, captureLimit);
            } catch (RuntimeException ex) {
                apiLog.warn("[OPENREACH-API] monitor_capture_failed type={} message={}", ex.getClass().getSimpleName(), compact(ex.getMessage()));
            }
            responseWrapper.copyBodyToResponse();
            apiLog.info("[OPENREACH-API] request_end status={} latencyMs={}", status, latencyMs);
            MDC.remove(TraceContext.API);
            MDC.remove(TraceContext.TRACE_ID);
        }
    }

    private void publishMonitorEvent(ContentCachingRequestWrapper request,
                                     ContentCachingResponseWrapper response,
                                     String traceId,
                                     long requestTimeMs,
                                     long latencyMs,
                                     byte[] fullResponseBody,
                                     int captureLimit) {
        byte[] requestBody = request.getContentAsByteArray();
        String requestPayload = payloadRedactor.redact(asString(
                requestBody, request.getContentType(), request.getCharacterEncoding(), captureLimit, true));
        String responsePayload = payloadRedactor.redact(asString(
                fullResponseBody, response.getContentType(), response.getCharacterEncoding(), captureLimit, true));
        boolean truncated = requestBody.length >= captureLimit && request.getContentLengthLong() > requestBody.length;
        truncated = truncated || fullResponseBody.length > captureLimit;

        JsonFields fields = parseJsonFields(responsePayload);
        int status = response.getStatus();
        monitorPublisher.publish(new MonitorRequestEvent(
                traceId,
                requestTimeMs,
                clientIp(request),
                request.getMethod(),
                request.getRequestURI(),
                requestPayload,
                responsePayload,
                status,
                status >= 200 && status < 300,
                latencyMs,
                fields.provider(),
                fields.errorCode(),
                fields.errorMessage(),
                request.getContentLengthLong() >= 0 ? request.getContentLengthLong() : requestBody.length,
                fullResponseBody.length,
                truncated,
                System.currentTimeMillis()
        ));
    }

    private JsonFields parseJsonFields(String payload) {
        if (payload == null || payload.isBlank()) return JsonFields.EMPTY;
        try {
            JsonNode root = jsonMapper.readTree(payload);
            if (root == null || !root.isObject()) return JsonFields.EMPTY;
            String provider = text(root, "provider");
            String errorCode = text(root, "code");
            String errorMessage = text(root, "message");
            return new JsonFields(provider, errorCode, errorMessage);
        } catch (Exception ignored) {
            return JsonFields.EMPTY;
        }
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText("");
        return text.isBlank() ? null : compact(text);
    }

    private String asString(byte[] bytes,
                            String contentType,
                            String servletEncoding,
                            int maxBytes,
                            boolean preferUtf8ForJson) {
        if (bytes == null || bytes.length == 0) return "";
        int length = Math.min(bytes.length, maxBytes);
        Charset charset = resolveCharset(contentType, servletEncoding, preferUtf8ForJson);
        return new String(bytes, 0, length, charset);
    }

    private Charset resolveCharset(String contentType, String servletEncoding, boolean preferUtf8ForJson) {
        Matcher matcher = CHARSET_PATTERN.matcher(contentType == null ? "" : contentType);
        if (matcher.find()) {
            try { return Charset.forName(matcher.group(1).trim()); } catch (Exception ignored) { }
        }

        String normalizedContentType = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        if (preferUtf8ForJson && (normalizedContentType.contains("application/json") || normalizedContentType.contains("+json"))) {
            // Spring/Jackson emits JSON as UTF-8 even when the Servlet API reports its historical
            // ISO-8859-1 default because no explicit charset parameter was added to Content-Type.
            return StandardCharsets.UTF_8;
        }

        if (servletEncoding != null && !servletEncoding.isBlank() && !"ISO-8859-1".equalsIgnoreCase(servletEncoding)) {
            try { return Charset.forName(servletEncoding); } catch (Exception ignored) { }
        }
        return StandardCharsets.UTF_8;
    }

    private String clientIp(HttpServletRequest request) {
        return ClientIpResolver.resolve(
                request.getRemoteAddr(),
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                properties.getMonitor().isTrustProxyHeaders(),
                properties.getMonitor().getTrustedProxyCidrs());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MONITORED_API_PATHS.contains(request.getRequestURI());
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
            case "/api/web/curl" -> "curl";
            default -> "api-other";
        };
    }

    private String safePath(String path) {
        if (path == null || path.isBlank()) return "/";
        return path.replaceAll("[\\r\\n\\t]", "_");
    }

    private String compact(String message) {
        if (message == null || message.isBlank()) return "-";
        String value = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record JsonFields(String provider, String errorCode, String errorMessage) {
        private static final JsonFields EMPTY = new JsonFields(null, null, null);
    }
}
