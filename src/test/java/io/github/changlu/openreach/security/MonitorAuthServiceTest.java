package io.github.changlu.openreach.security;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorAuthServiceTest {
    @Test
    void defaultsToOpenreachCredentialsAndSupportsConfigurationOverride() {
        WebCapabilityProperties properties = new WebCapabilityProperties();
        MonitorAuthService auth = new MonitorAuthService(properties);

        MockHttpServletRequest defaults = new MockHttpServletRequest();
        assertTrue(auth.authenticate(defaults, "openreach", "openreach"));
        assertTrue(auth.isAuthenticated(defaults));

        properties.getMonitor().setUsername("admin");
        properties.getMonitor().setPassword("secret");

        MockHttpServletRequest old = new MockHttpServletRequest();
        assertFalse(auth.authenticate(old, "openreach", "openreach"));

        MockHttpServletRequest overridden = new MockHttpServletRequest();
        assertTrue(auth.authenticate(overridden, "admin", "secret"));
    }
}
