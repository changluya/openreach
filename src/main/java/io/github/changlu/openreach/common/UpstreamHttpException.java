package io.github.changlu.openreach.common;

/**
 * Structured upstream HTTP failure used by Read so callers can distinguish
 * target-site access policy from retryable server-side/transient failures.
 */
public class UpstreamHttpException extends UpstreamException {
    private final int statusCode;
    private final boolean retryable;

    public UpstreamHttpException(int statusCode, boolean retryable) {
        super("Upstream returned HTTP " + statusCode);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
