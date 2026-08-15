package io.github.changlu.openreach;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(WebCapabilityProperties.class)
public class OpenReachApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpenReachApplication.class, args);
    }
}
