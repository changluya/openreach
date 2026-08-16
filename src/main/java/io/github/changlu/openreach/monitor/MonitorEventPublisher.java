package io.github.changlu.openreach.monitor;

import io.github.changlu.openreach.monitor.model.MonitorRequestEvent;

public interface MonitorEventPublisher {
    void publish(MonitorRequestEvent event);
}
