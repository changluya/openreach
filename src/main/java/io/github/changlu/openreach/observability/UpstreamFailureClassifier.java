package io.github.changlu.openreach.observability;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;

public final class UpstreamFailureClassifier {
    private UpstreamFailureClassifier() {}

    public static String classify(Throwable error) {
        if (error == null) return "UNKNOWN";
        StringBuilder messages = new StringBuilder();
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof HttpTimeoutException || cursor instanceof SocketTimeoutException) return "TIMEOUT";
            if (cursor.getMessage() != null) messages.append(' ').append(cursor.getMessage());
            cursor = cursor.getCause();
        }
        String message = messages.toString().toLowerCase(Locale.ROOT);
        if (message.contains("certificate_unknown")
                || message.contains("no subject alternative dns name")
                || message.contains("pkix path")
                || message.contains("sslhandshake")) return "TLS_CERTIFICATE";
        if (message.contains("http 403") || message.contains("http=403")) return "HTTP_403";
        if (message.contains("http 429") || message.contains("http=429")) return "HTTP_429";
        if (containsHttp5xx(message)) return "HTTP_5XX";
        if (containsHttp3xx(message) || message.contains("redirect limit") || message.contains("too many redirects")) return "HTTP_REDIRECT";
        if (message.contains("bot challenge") || message.contains("captcha")) return "BOT_CHALLENGE";
        if (message.contains("timed out") || message.contains("timeout")) return "TIMEOUT";
        if (message.contains("interrupted")) return "INTERRUPTED";
        if (message.contains("exceeds configured") || message.contains("too large")) return "RESPONSE_TOO_LARGE";
        if (message.contains("no parsable") || message.contains("empty result")) return "PARSE_EMPTY";
        if (message.contains("timerange unsupported") || message.contains("does not support timerange")) return "UNSUPPORTED_CAPABILITY";
        if (message.contains("failed to read url") || message.contains("request failed")) return "IO_ERROR";
        return "UPSTREAM_ERROR";
    }

    private static boolean containsHttp5xx(String message) {
        for (int code = 500; code <= 599; code++) {
            if (message.contains("http " + code) || message.contains("http=" + code)) return true;
        }
        return false;
    }

    private static boolean containsHttp3xx(String message) {
        for (int code = 300; code <= 399; code++) {
            if (message.contains("http " + code) || message.contains("http=" + code)) return true;
        }
        return false;
    }
}
