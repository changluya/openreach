package io.github.changlu.openreach.monitor.store;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the core application bootable even when a future/unsupported monitor storage type is configured.
 * Adding MySQL/PostgreSQL later only requires registering another MonitorRecordStore bean.
 */
@Configuration
public class MonitorStoreConfiguration {

    @Bean
    @ConditionalOnMissingBean(MonitorRecordStore.class)
    MonitorRecordStore unavailableMonitorRecordStore(WebCapabilityProperties properties) {
        return new UnavailableMonitorRecordStore(properties.getMonitor().getStorage());
    }
}
