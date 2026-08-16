package io.github.changlu.openreach;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "openreach.web.monitor.data-dir=target/test-monitor-data")
class OpenReachApplicationTest {
    @Test
    void contextLoads() {
    }
}
