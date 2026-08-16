package io.github.changlu.openreach.monitor;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class PayloadRedactor {
    private static final Pattern SENSITIVE_STRING = Pattern.compile(
            "(?i)(\\\"(?:authorization|cookie|set-cookie|password|passwd|token|access[_-]?token|refresh[_-]?token|api[_-]?key|secret|client[_-]?secret)\\\"\\s*:\\s*)\\\"(?:\\\\.|[^\\\"])*\\\"");
    private static final Pattern SENSITIVE_PRIMITIVE = Pattern.compile(
            "(?i)(\\\"(?:authorization|cookie|set-cookie|password|passwd|token|access[_-]?token|refresh[_-]?token|api[_-]?key|secret|client[_-]?secret)\\\"\\s*:\\s*)(?!\\\")(?:true|false|null|-?\\d+(?:\\.\\d+)?)");

    public String redact(String payload) {
        if (payload == null || payload.isBlank()) return payload;
        String redacted = SENSITIVE_STRING.matcher(payload).replaceAll("$1\\\"***REDACTED***\\\"");
        return SENSITIVE_PRIMITIVE.matcher(redacted).replaceAll("$1\\\"***REDACTED***\\\"");
    }
}
