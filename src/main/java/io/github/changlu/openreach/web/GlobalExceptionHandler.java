package io.github.changlu.openreach.web;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.common.UpstreamHttpException;
import io.github.changlu.openreach.observability.TraceContext;
import io.github.changlu.openreach.monitor.MonitorStorageUnavailableException;
import io.github.changlu.openreach.observability.UpstreamFailureClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Logger apiLog = LoggerFactory.getLogger("OPENREACH.API");

    @ExceptionHandler({BadRequestException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception ex) {
        apiLog.warn("[OPENREACH-API] request_rejected code=BAD_REQUEST type={} message={}", ex.getClass().getSimpleName(), safeClientMessage(ex.getMessage(), "Invalid request"));
        return response(HttpStatus.BAD_REQUEST, "BAD_REQUEST", safeClientMessage(ex.getMessage(), "Invalid request"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse("validation failed");
        apiLog.warn("[OPENREACH-API] request_rejected code=VALIDATION_ERROR message={}", safeClientMessage(message, "validation failed"));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex) {
        apiLog.warn("[OPENREACH-API] request_rejected code=INVALID_JSON type={}", ex.getClass().getSimpleName());
        return response(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Malformed or unreadable JSON request body");
    }

    @ExceptionHandler(UpstreamException.class)
    public ResponseEntity<Map<String, Object>> upstream(UpstreamException ex) {
        String failureType = UpstreamFailureClassifier.classify(ex);
        apiLog.error("[OPENREACH-API] request_failed code=UPSTREAM_ERROR type={} message={}",
                failureType, safeClientMessage(ex.getMessage(), "Upstream request failed"));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("failureType", failureType);
        if (ex instanceof UpstreamHttpException http) {
            extra.put("upstreamStatus", http.getStatusCode());
            extra.put("retryable", http.isRetryable());
        }
        return response(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR",
                safeClientMessage(ex.getMessage(), "Upstream request failed"), extra);
    }

    @ExceptionHandler(MonitorStorageUnavailableException.class)
    public ResponseEntity<Map<String, Object>> monitorUnavailable(MonitorStorageUnavailableException ex) {
        log.warn("Monitor storage unavailable: {}", safeClientMessage(ex.getMessage(), "Monitor storage unavailable"));
        return response(HttpStatus.SERVICE_UNAVAILABLE, "MONITOR_STORAGE_UNAVAILABLE", "Monitor storage is temporarily unavailable");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException ex) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unknown(Exception ex) {
        log.error("Unhandled OpenReach request failure", ex);
        apiLog.error("[OPENREACH-API] request_failed code=INTERNAL_ERROR type={}", ex.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error");
    }

    private String safeClientMessage(String message, String fallback) {
        if (message == null || message.isBlank()) return fallback;
        String oneLine = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return oneLine.length() <= 500 ? oneLine : oneLine.substring(0, 500);
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String code, String message) {
        return response(status, code, message, Map.of());
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String code, String message,
                                                          Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("code", code);
        body.put("traceId", TraceContext.traceId());
        body.put("message", message == null ? status.getReasonPhrase() : message);
        if (extra != null && !extra.isEmpty()) body.putAll(extra);
        return ResponseEntity.status(status).body(body);
    }
}
