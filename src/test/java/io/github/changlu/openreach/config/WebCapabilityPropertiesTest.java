package io.github.changlu.openreach.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebCapabilityPropertiesTest {

    @Test
    void currentDefaultsKeepCnCompatibilityAndProvideGlobalChains() {
        WebCapabilityProperties props = new WebCapabilityProperties();

        assertEquals("cn", props.getRouting().getDefaultRoute());
        assertEquals(List.of("bing", "baidu", "sogou", "so360", "duckduckgo"),
                props.getSearch().effectiveCnProviderOrder());
        assertEquals(List.of("brave", "duckduckgo", "bing"),
                props.getSearch().getGlobalProviderOrder());
        assertEquals(List.of("baidu", "bing", "duckduckgo", "brave"), props.getSearch().getCnTimeRangeProviderOrder());
        assertEquals(List.of("bing", "brave", "duckduckgo", "baidu"), props.getSearch().getGlobalTimeRangeProviderOrder());
        assertEquals(2 * 1024 * 1024, props.getSearch().getMaxResponseBytes());
        assertEquals(5, props.getSearch().getMaxRedirects());
        assertEquals("https://www.so.com/index.php", props.getSearch().getSo360Url());
        assertEquals(List.of("bing", "baidu", "sogou", "openverse"),
                props.getImageSearch().effectiveCnProviderOrder());
        assertEquals(List.of("bing", "openverse", "wikimedia"),
                props.getImageSearch().getGlobalProviderOrder());
        assertEquals(4 * 1024 * 1024, props.getImageSearch().getMaxResponseBytes());
        assertEquals(6, props.getImageSearch().getDownloadValidationConcurrency());
        assertEquals(48, props.getImageSearch().getDownloadValidationQueueCapacity());
        assertEquals("OpenReach/0.1.3 (+https://github.com/changluya/openreach)", props.getImageSearch().getWikimediaUserAgent());
    }

    @Test
    void monitorDefaultsAreAvailableForZeroConfigurationStartup() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        assertEquals("openreach", props.getMonitor().getUsername());
        assertEquals("openreach", props.getMonitor().getPassword());
        assertEquals(7200, props.getMonitor().getSessionTimeoutSeconds());
        assertEquals("sqlite", props.getMonitor().getStorage());
        assertEquals("./data/monitor", props.getMonitor().getDataDir());
        assertEquals("openreach-monitor.db", props.getMonitor().getSqliteFile());
        assertEquals(10_000, props.getMonitor().getQueueCapacity());
        assertEquals(100, props.getMonitor().getBatchSize());
        assertEquals(30, props.getMonitor().getMetadataRetentionDays());
        assertEquals(7, props.getMonitor().getPayloadRetentionDays());
        assertTrue(props.getMonitor().isTrustProxyHeaders());
        assertEquals(List.of("127.0.0.1/32", "::1/128", "172.16.0.0/12"), props.getMonitor().getTrustedProxyCidrs());

        String yaml;
        try (var stream = WebCapabilityPropertiesTest.class.getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(stream);
            yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertTrue(yaml.contains("${OPENREACH_MONITOR_USERNAME:openreach}"));
        assertTrue(yaml.contains("${OPENREACH_MONITOR_PASSWORD:openreach}"));
        assertTrue(yaml.contains("${OPENREACH_MONITOR_TRUST_PROXY_HEADERS:true}"));
        assertTrue(yaml.contains("${OPENREACH_MONITOR_TRUSTED_PROXY_CIDRS:127.0.0.1/32,::1/128,172.16.0.0/12}"));
    }

    @Test
    void readTimeoutCompatibilityAndRetryDefaultsAreBounded() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        assertEquals(10_000, props.getRead().effectiveConnectTimeoutMs());
        assertEquals(10_000, props.getRead().effectiveRequestTimeoutMs());
        assertEquals(2, props.getRead().effectiveMaxAttempts());
        assertEquals(200, props.getRead().effectiveRetryBackoffMs());

        props.getRead().setTimeoutMs(12_000);
        assertEquals(12_000, props.getRead().effectiveConnectTimeoutMs());
        assertEquals(12_000, props.getRead().effectiveRequestTimeoutMs());

        props.getRead().setConnectTimeoutMs(7_000);
        props.getRead().setRequestTimeoutMs(15_000);
        props.getRead().setMaxAttempts(0);
        props.getRead().setRetryBackoffMs(-1);
        assertEquals(7_000, props.getRead().effectiveConnectTimeoutMs());
        assertEquals(15_000, props.getRead().effectiveRequestTimeoutMs());
        assertEquals(1, props.getRead().effectiveMaxAttempts());
        assertEquals(0, props.getRead().effectiveRetryBackoffMs());
    }

    @Test
    void imageSearchDoesNotExposeSearchOnlyTimeRangeProviderOrder() {
        assertThrows(NoSuchMethodException.class,
                () -> WebCapabilityProperties.ImageSearch.class.getMethod("getCnTimeRangeProviderOrder"));
        assertThrows(NoSuchMethodException.class,
                () -> WebCapabilityProperties.ImageSearch.class.getMethod("getGlobalTimeRangeProviderOrder"));
    }

    @Test
    void bundledApplicationYamlDoesNotShadowLegacyProviderOrder() throws IOException {
        var stream = WebCapabilityPropertiesTest.class.getClassLoader().getResourceAsStream("application.yml");
        assertNotNull(stream);
        String yaml;
        try (stream) {
            yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertEquals(2, occurrences(yaml, "cn-provider-order: []"));
    }

    @Test
    void explicitCnOrderOverridesLegacyOrderWhileEmptyCnOrderFallsBack() {
        WebCapabilityProperties props = new WebCapabilityProperties();

        props.getSearch().setProviderOrder(List.of("legacy-search"));
        props.getSearch().setCnProviderOrder(List.of());
        assertEquals(List.of("legacy-search"), props.getSearch().effectiveCnProviderOrder());

        props.getSearch().setCnProviderOrder(List.of("new-cn-search"));
        assertEquals(List.of("new-cn-search"), props.getSearch().effectiveCnProviderOrder());

        props.getImageSearch().setProviderOrder(List.of("legacy-image"));
        props.getImageSearch().setCnProviderOrder(null);
        assertEquals(List.of("legacy-image"), props.getImageSearch().effectiveCnProviderOrder());

        props.getImageSearch().setCnProviderOrder(List.of("new-cn-image"));
        assertEquals(List.of("new-cn-image"), props.getImageSearch().effectiveCnProviderOrder());
    }

    private int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
