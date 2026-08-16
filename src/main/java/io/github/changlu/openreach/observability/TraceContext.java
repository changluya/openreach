package io.github.changlu.openreach.observability;

import org.slf4j.MDC;

public final class TraceContext {
    public static final String TRACE_ID = "traceId";
    public static final String API = "api";

    private TraceContext() {}

    public static String traceId() {
        String value = MDC.get(TRACE_ID);
        return value == null || value.isBlank() ? "NA" : value;
    }

    public static String api() {
        String value = MDC.get(API);
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
