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
        assertNotNull(loader.getResource("static/changelog.html"));
        assertNotNull(loader.getResource("static/assets/site.css"));
        assertNotNull(loader.getResource("static/assets/logo.png"));
        assertNotNull(loader.getResource("static/assets/logo-mark.png"));
        assertNotNull(loader.getResource("static/assets/site.js"));
        assertNotNull(loader.getResource("static/assets/theme-init.js"));
        assertNotNull(loader.getResource("static/assets/wechat-group.png"));
        assertNotNull(loader.getResource("static/downloads/openreach-skill.zip"));
        assertNotNull(loader.getResource("logback-spring.xml"));
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
        String changelog = read("static/changelog.html");
        assertTrue(home.contains("/assets/theme-init.js"));
        assertTrue(quickStart.contains("/assets/theme-init.js"));
        assertTrue(api.contains("/assets/theme-init.js"));
        assertTrue(changelog.contains("/assets/theme-init.js"));
        org.junit.jupiter.api.Assertions.assertFalse(home.contains("<script>"));
        org.junit.jupiter.api.Assertions.assertFalse(quickStart.contains("<script>"));
        org.junit.jupiter.api.Assertions.assertFalse(api.contains("<script>"));
        org.junit.jupiter.api.Assertions.assertFalse(changelog.contains("<script>"));
        org.junit.jupiter.api.Assertions.assertFalse(quickStart.contains("/api/web/health"));
        org.junit.jupiter.api.Assertions.assertFalse(api.contains("/api/web/health"));
    }


    @Test
    void copyButtonsSupportHttpFallbackAndVisibleFeedback() throws IOException {
        String script = read("static/assets/site.js");
        String style = read("static/assets/site.css");
        assertTrue(script.contains("window.isSecureContext"));
        assertTrue(script.contains("navigator.clipboard.writeText"));
        assertTrue(script.contains("document.execCommand('copy')"));
        assertTrue(script.contains("legacyCopyText"));
        assertTrue(script.contains("复制失败"));
        assertTrue(style.contains(".copy-btn.is-success"));
        assertTrue(style.contains(".copy-btn.is-error"));
    }

    @Test
    void everyCopyButtonPointsToAnExistingElement() throws IOException {
        for (String resource : new String[]{"static/index.html", "static/docs/index.html", "static/docs/api.html"}) {
            String html = read(resource);
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("data-copy=\"#([^\"]+)\"").matcher(html);
            int count = 0;
            while (matcher.find()) {
                count++;
                String id = matcher.group(1);
                assertTrue(html.contains("id=\"" + id + "\""), resource + " missing copy target #" + id);
            }
            assertTrue(count > 0, resource + " should contain at least one copy button");
        }
    }

    @Test
    void websiteExposesLatestChangelogNavigation() throws IOException {
        String home = read("static/index.html");
        String docs = read("static/docs/index.html");
        String api = read("static/docs/api.html");
        String changelog = read("static/changelog.html");
        assertChangelogNavigation(home, "static/index.html");
        assertChangelogNavigation(docs, "static/docs/index.html");
        assertChangelogNavigation(api, "static/docs/api.html");
        assertTrue(changelog.contains("v0.1.2"));
        assertTrue(changelog.contains("timeRange"));
        assertTrue(changelog.contains("只返回可下载原图"));
        assertTrue(changelog.contains("公网攻击面 Allowlist"));
    }

    private void assertChangelogNavigation(String html, String resource) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "<a\\b[^>]*href=\"/changelog\"[^>]*>\\s*更新日志\\s*</a>",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );
        assertTrue(pattern.matcher(html).find(), resource + " should expose /changelog navigation");
    }

    private String read(String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
