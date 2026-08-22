package io.github.changlu.openreach;

import io.github.changlu.openreach.curl.CurlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = "openreach.web.monitor.data-dir=target/test-monitor-data")
class OpenReachApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoadsAndWiresCurlService() {
        assertNotNull(applicationContext.getBean(CurlService.class));
    }
}
