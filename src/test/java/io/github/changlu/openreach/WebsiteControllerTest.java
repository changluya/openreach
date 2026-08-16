package io.github.changlu.openreach;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.security.MonitorAuthService;
import io.github.changlu.openreach.web.WebsiteController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebsiteControllerTest {

    private final WebCapabilityProperties properties = new WebCapabilityProperties();
    private final MonitorAuthService authService = new MonitorAuthService(properties);
    private final WebsiteController controller = new WebsiteController(authService);

    @Test
    void docsDirectoryRouteForwardsToStaticIndex() {
        assertEquals("forward:/docs/index.html", controller.docs());
    }

    @Test
    void changelogDirectoryRouteForwardsToStaticPage() {
        assertEquals("forward:/changelog.html", controller.changelog());
    }

    @Test
    void monitorRouteRequiresSessionAndForwardsAfterAuthentication() {
        MockHttpServletRequest anonymous = new MockHttpServletRequest("GET", "/monitor");
        assertEquals("redirect:/monitor/login", controller.monitor(anonymous));

        MockHttpServletRequest authenticated = new MockHttpServletRequest("GET", "/monitor");
        assertTrue(authService.authenticate(authenticated, "openreach", "openreach"));
        assertEquals("forward:/monitor.html", controller.monitor(authenticated));
    }

    @Test
    void monitorLoginUsesConfiguredDefaultCredentialsAndCreatesSession() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/monitor/login");
        assertEquals("redirect:/monitor", controller.monitorLogin("openreach", "openreach", request));
        assertTrue(authService.isAuthenticated(request));
    }

    @Test
    void monitorLoginRejectsInvalidCredentials() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/monitor/login");
        assertEquals("redirect:/monitor/login?error=1", controller.monitorLogin("openreach", "bad", request));
    }

    @Test
    void monitorLogoutInvalidatesAuthenticatedSession() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/monitor/logout");
        assertTrue(authService.authenticate(request, "openreach", "openreach"));
        assertEquals("redirect:/monitor/login?logout=1", controller.monitorLogout(request));
        org.junit.jupiter.api.Assertions.assertFalse(authService.isAuthenticated(request));
    }

    @Test
    void communityRouteRedirectsToDocsCommunitySection() {
        assertEquals("redirect:/docs/#community", controller.community());
    }
}
