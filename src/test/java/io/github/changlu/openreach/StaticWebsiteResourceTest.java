package io.github.changlu.openreach;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticWebsiteResourceTest {

    @Test
    void officialWebsiteAndDocsArePackaged() {
        ClassLoader loader = getClass().getClassLoader();
        assertNotNull(loader.getResource("static/index.html"));
        assertNotNull(loader.getResource("static/docs/index.html"));
        assertNotNull(loader.getResource("static/docs/api.html"));
        assertNotNull(loader.getResource("static/assets/site.css"));
        assertNotNull(loader.getResource("static/assets/logo.png"));
        assertNotNull(loader.getResource("static/assets/logo-mark.png"));
        assertNotNull(loader.getResource("static/assets/site.js"));
        assertNotNull(loader.getResource("static/assets/theme-init.js"));
        assertNotNull(loader.getResource("static/assets/wechat-group.png"));
        assertNotNull(loader.getResource("static/downloads/openreach-skill.zip"));
    }

    @Test
    void websiteQuickStartUsesSingleDockerRunCommand() throws IOException {
        String home = read("static/index.html");
        String docs = read("static/docs/index.html");
        assertTrue(home.contains("docker run -d"));
        assertTrue(docs.contains("Docker 一键启动（推荐）"));
        assertTrue(docs.contains("docker run -d"));
        assertTrue(docs.contains("Docker Compose（可选）"));
    }

    @Test
    void docsCommunityQrSupportsClickToPreview() throws IOException {
        String quickStart = read("static/docs/index.html");
        String api = read("static/docs/api.html");
        String script = read("static/assets/site.js");
        String style = read("static/assets/site.css");
        assertTrue(quickStart.contains("data-qr-preview"));
        assertTrue(api.contains("data-qr-preview"));
        assertTrue(script.contains("openQrPreview"));
        assertTrue(script.contains("closeQrPreview"));
        assertTrue(style.contains(".qr-modal"));
    }

    @Test
    void websiteUsesExternalScriptsAndDoesNotDocumentRemovedHealthApi() throws IOException {
        String home = read("static/index.html");
        String quickStart = read("static/docs/index.html");
        String api = read("static/docs/api.html");
        assertTrue(home.contains("/assets/theme-init.js"));
        assertTrue(quickStart.contains("/assets/theme-init.js"));
        assertTrue(api.contains("/assets/theme-init.js"));
        org.junit.jupiter.api.Assertions.assertFalse(home.contains("<script>"));
        org.junit.jupiter.api.Assertions.assertFalse(quickStart.contains("<script>"));
        org.junit.jupiter.api.Assertions.assertFalse(api.contains("<script>"));
        org.junit.jupiter.api.Assertions.assertFalse(quickStart.contains("/api/web/health"));
        org.junit.jupiter.api.Assertions.assertFalse(api.contains("/api/web/health"));
    }

    private String read(String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
