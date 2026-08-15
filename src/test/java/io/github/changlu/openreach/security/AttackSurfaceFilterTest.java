package io.github.changlu.openreach.security;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackSurfaceFilterTest {

    private final WebCapabilityProperties props = new WebCapabilityProperties();
    private final AttackSurfaceFilter filter = new AttackSurfaceFilter(props);

    @Test
    void allowsOnlyThreeJsonApiPathsToReachApplication() throws Exception {
        for (String path : new String[]{"/api/web/search", "/api/web/image-search", "/api/web/read"}) {
            MockHttpServletRequest req = jsonPost(path, "{}");
            MockHttpServletResponse res = new MockHttpServletResponse();
            AtomicBoolean reached = new AtomicBoolean(false);
            filter.doFilter(req, res, (request, response) -> reached.set(true));
            assertTrue(reached.get(), path);
            assertEquals("no-store", res.getHeader("Cache-Control"));
        }
    }

    @Test
    void blocksRemovedHealthActuatorUploadAndUnknownEndpoints() throws Exception {
        for (String path : new String[]{"/api/web/health", "/actuator/env", "/upload", "/api/upload", "/debug", "/error"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse res = new MockHttpServletResponse();
            AtomicBoolean reached = new AtomicBoolean(false);
            filter.doFilter(req, res, (request, response) -> reached.set(true));
            assertEquals(404, res.getStatus(), path);
            assertTrue(!reached.get(), path);
        }
    }

    @Test
    void blocksMultipartEvenWhenTargetIsAValidApi() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/web/search");
        req.setContentType("multipart/form-data; boundary=x");
        req.setContent("--x\r\nContent-Disposition: form-data; name=\"file\"; filename=\"shell.jsp\"\r\n\r\npwn\r\n--x--".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean(false);

        filter.doFilter(req, res, (request, response) -> reached.set(true));

        assertEquals(415, res.getStatus());
        assertTrue(res.getContentAsString().contains("UPLOAD_DISABLED"));
        assertTrue(!reached.get());
    }

    @Test
    void blocksDangerousHttpMethodsOnApiAndStaticResources() throws Exception {
        MockHttpServletRequest api = new MockHttpServletRequest("PUT", "/api/web/search");
        api.setContentType("application/json");
        MockHttpServletResponse apiRes = new MockHttpServletResponse();
        filter.doFilter(api, apiRes, (request, response) -> {});
        assertEquals(405, apiRes.getStatus());

        MockHttpServletRequest staticReq = new MockHttpServletRequest("POST", "/assets/site.js");
        MockHttpServletResponse staticRes = new MockHttpServletResponse();
        filter.doFilter(staticReq, staticRes, (request, response) -> {});
        assertEquals(405, staticRes.getStatus());
    }

    @Test
    void blocksOversizedApiBodyIncludingBodiesThatMustBeActuallyRead() throws Exception {
        props.getSecurity().setMaxApiBodyBytes(1024);
        MockHttpServletRequest req = jsonPost("/api/web/search", "x".repeat(2048));
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (request, response) -> {});

        assertEquals(413, res.getStatus());
        assertTrue(res.getContentAsString().contains("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void blocksTraversalAndEncodedPathVariantsBeforeStaticDispatch() throws Exception {
        for (String path : new String[]{"/assets/../application.yml", "/assets/%2e%2e/application.yml", "/assets/%252e%252e/application.yml", "/assets/a\\b.js"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, (request, response) -> {});
            assertEquals(400, res.getStatus(), path);
        }
    }

    @Test
    void staticWebsiteReceivesDefensiveBrowserHeaders() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean(false);

        filter.doFilter(req, res, (request, response) -> reached.set(true));

        assertTrue(reached.get());
        assertEquals("nosniff", res.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", res.getHeader("X-Frame-Options"));
        assertTrue(res.getHeader("Content-Security-Policy").contains("script-src 'self'"));
        assertTrue(res.getHeader("Content-Security-Policy").contains("object-src 'none'"));
    }

    private MockHttpServletRequest jsonPost(String path, String body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setContentType("application/json");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        return req;
    }
}
