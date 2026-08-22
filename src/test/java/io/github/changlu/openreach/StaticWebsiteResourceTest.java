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
        assertNotNull(loader.getResource("static/monitor.html"));
        assertNotNull(loader.getResource("static/monitor-login.html"));
        assertNotNull(loader.getResource("static/assets/site.css"));
        assertNotNull(loader.getResource("static/assets/monitor.css"));
        assertNotNull(loader.getResource("static/assets/monitor.js"));
        assertNotNull(loader.getResource("static/assets/monitor-login.css"));
        assertNotNull(loader.getResource("static/assets/monitor-login.js"));
        assertNotNull(loader.getResource("static/assets/logo.png"));
        assertNotNull(loader.getResource("static/assets/logo-mark.png"));
        assertNotNull(loader.getResource("static/assets/site.js"));
        assertNotNull(loader.getResource("static/assets/theme-init.js"));
        assertNotNull(loader.getResource("static/assets/wechat-group.png"));
        assertNotNull(loader.getResource("static/downloads/openreach-skill.zip"));
        assertNotNull(loader.getResource("logback-spring.xml"));
        assertNotNull(loader.getResource("db/monitor/sqlite/V001__init.sql"));
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
        String monitor = read("static/monitor.html");
        assertTrue(home.contains("/assets/theme-init.js"));
        assertTrue(quickStart.contains("/assets/theme-init.js"));
        assertTrue(api.contains("/assets/theme-init.js"));
        assertTrue(changelog.contains("/assets/theme-init.js"));
        assertTrue(monitor.contains("/assets/theme-init.js"));
        assertTrue(monitor.contains("/assets/monitor.js"));
        org.junit.jupiter.api.Assertions.assertFalse(home.contains("<script>"));
        org.junit.jupiter.api.Assertions.assertFalse(quickStart.contains("<script>"));
        org.junit.jupiter.api.Assertions.assertFalse(api.contains("<script>"));
        org.junit.jupiter.api.Assertions.assertFalse(changelog.contains("<script>"));
        org.junit.jupiter.api.Assertions.assertFalse(monitor.contains("<script>"));
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
    void monitorDashboardUsesRealApiAndExposesFailureDrilldownAndRequestDetails() throws IOException {
        String monitor = read("static/monitor.html");
        String login = read("static/monitor-login.html");
        String script = read("static/assets/monitor.js");
        assertTrue(monitor.contains("/monitor/logout"));
        assertTrue(login.contains("action=\"/monitor/login\""));
        assertTrue(login.contains("name=\"username\""));
        assertTrue(login.contains("name=\"password\""));
        assertTrue(monitor.contains("data-period=\"today\""));
        assertTrue(monitor.contains("data-period=\"7d\""));
        assertTrue(monitor.contains("id=\"date-range-trigger\""));
        assertTrue(monitor.contains("id=\"range-start\""));
        assertTrue(monitor.contains("id=\"range-end\""));
        assertTrue(monitor.contains("应用范围"));
        assertTrue(monitor.contains("调用失败"));
        assertTrue(monitor.contains("id=\"export-failure-records\""));
        assertTrue(monitor.contains("导出失败请求"));
        assertTrue(monitor.contains("id=\"request-time-start\""));
        assertTrue(monitor.contains("id=\"request-time-end\""));
        assertTrue(monitor.contains("type=\"datetime-local\""));
        assertTrue(monitor.contains("id=\"request-time-apply\""));
        assertTrue(monitor.contains("id=\"request-time-clear\""));
        assertTrue(monitor.contains("输入参数"));
        assertTrue(monitor.contains("输出参数"));
        assertTrue(monitor.contains("IP 地址"));
        assertTrue(monitor.contains("请求状态"));
        assertTrue(monitor.contains("耗时"));
        assertTrue(script.contains("failureCard.addEventListener"));
        assertTrue(script.contains("applyCustomRange"));
        assertTrue(script.contains("boundsForPeriod"));
        assertTrue(script.contains("openDetail"));
        assertTrue(script.contains("/api/monitor/overview"));
        assertTrue(script.contains("/api/monitor/trend"));
        assertTrue(script.contains("/api/monitor/distribution"));
        assertTrue(script.contains("/api/monitor/records"));
        assertTrue(script.contains("/api/monitor/records/export"));
        assertTrue(script.contains("recordApiParams"));
        assertTrue(script.contains("requestStartTimeMs"));
        assertTrue(script.contains("requestEndTimeMs"));
        assertTrue(script.contains("applyRequestTimeRange"));
        assertTrue(script.contains("clearRequestTimeRange"));
        assertTrue(script.contains("exportFailureRecords"));
        assertTrue(script.contains("buildApiUrl"));
        assertTrue(script.contains("response.blob()"));
        assertTrue(script.contains("credentials: 'same-origin'"));
        assertTrue(script.contains("openreach-failed-requests-"));
        org.junit.jupiter.api.Assertions.assertFalse(script.contains("const url = apiUrl(path, params)"));
        org.junit.jupiter.api.Assertions.assertFalse(script.contains("createMockRecords"));
        assertTrue(monitor.contains("SQLite"));
        assertTrue(script.contains("Trace ID"));
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
        assertNoMonitorNavigation(home, "static/index.html");
        assertNoMonitorNavigation(docs, "static/docs/index.html");
        assertNoMonitorNavigation(api, "static/docs/api.html");
        assertNoMonitorNavigation(changelog, "static/changelog.html");
        assertTrue(changelog.contains("v0.1.4"));
        assertTrue(changelog.contains("v0.1.3"));
        assertTrue(changelog.contains("v0.1.2"));
        assertTrue(changelog.contains("timeRange"));
        assertTrue(changelog.contains("只返回可下载原图"));
        assertTrue(changelog.contains("公网攻击面 Allowlist"));
        assertTrue(changelog.contains("SQLite + WAL"));
        assertTrue(changelog.contains("失败请求后端导出"));
        assertTrue(changelog.contains("Schema V2"));
        assertTrue(home.contains("POST /api/web/curl"));
        assertTrue(docs.contains("Safe Curl"));
        assertTrue(api.contains("/api/web/curl"));
        assertTrue(changelog.contains("Self Block"));
    }

    private void assertChangelogNavigation(String html, String resource) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "<a\\b[^>]*href=\"/changelog\"[^>]*>\\s*更新日志\\s*</a>",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );
        assertTrue(pattern.matcher(html).find(), resource + " should expose /changelog navigation");
    }

    private void assertNoMonitorNavigation(String html, String resource) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "<a\\b[^>]*href=\"/monitor\"",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );
        org.junit.jupiter.api.Assertions.assertFalse(pattern.matcher(html).find(), resource + " must not expose /monitor navigation");
    }

    private String read(String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
