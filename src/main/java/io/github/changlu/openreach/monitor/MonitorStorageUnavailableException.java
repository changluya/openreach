package io.github.changlu.openreach.monitor;

public class MonitorStorageUnavailableException extends RuntimeException {
    public MonitorStorageUnavailableException(String message) {
        super(message);
    }

    public MonitorStorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
