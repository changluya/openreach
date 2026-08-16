package io.github.changlu.openreach.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebCapabilityPropertiesTest {

    @Test
    void v102DefaultsKeepCnCompatibilityAndProvideGlobalChains() {
        WebCapabilityProperties props = new WebCapabilityProperties();

        assertEquals("cn", props.getRouting().getDefaultRoute());
        assertEquals(List.of("bing", "baidu", "sogou", "so360", "duckduckgo"),
                props.getSearch().effectiveCnProviderOrder());
        assertEquals(List.of("brave", "duckduckgo", "bing"),
                props.getSearch().getGlobalProviderOrder());
        assertEquals(List.of("baidu", "bing", "duckduckgo", "brave"), props.getSearch().getCnTimeRangeProviderOrder());
        assertEquals(List.of("bing", "brave", "duckduckgo", "baidu"), props.getSearch().getGlobalTimeRangeProviderOrder());
        assertEquals(2 * 1024 * 1024, props.getSearch().getMaxResponseBytes());
        assertEquals(List.of("bing", "baidu", "sogou", "openverse"),
                props.getImageSearch().effectiveCnProviderOrder());
        assertEquals(List.of("bing", "openverse", "wikimedia"),
                props.getImageSearch().getGlobalProviderOrder());
        assertEquals(4 * 1024 * 1024, props.getImageSearch().getMaxResponseBytes());
        assertEquals(6, props.getImageSearch().getDownloadValidationConcurrency());
        assertEquals(48, props.getImageSearch().getDownloadValidationQueueCapacity());
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
