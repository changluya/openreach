package io.github.changlu.openreach.imagesearch.provider;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SogouImageSearchProviderTest {
    @Test
    void parsesInitialState() {
        String html = """
                <html><script>window.__INITIAL_STATE__ = {"searchList":{"searchList":[{"url":"https://example.com/p","picUrl":"https://img.example/p.png","title":"西湖","content_major":"杭州西湖","ch_site_name":"示例站","width":800,"height":600}]}};</script></html>
                """;
        var provider = new SogouImageSearchProvider(null, new WebCapabilityProperties(), JsonMapper.builder().build());
        var items = provider.parseResults(html, 5);
        assertEquals(1, items.size());
        assertEquals("sogou", items.get(0).provider());
        assertEquals("西湖", items.get(0).title());
        assertEquals("示例站", items.get(0).source());
        assertEquals("png", items.get(0).imageFormat());
    }

    @Test
    void extractsNestedInitialStateWithoutRegexTruncation() {
        String html = """
                <script>
                window.__INITIAL_STATE__ = {"outer":{"nested":{"message":"brace } and semicolon }; stay inside string"}},"items":[{"a":1}]};
                </script>
                """;
        var provider = new SogouImageSearchProvider(null, new WebCapabilityProperties(), JsonMapper.builder().build());
        String json = provider.extractInitialStateJson(html);
        assertTrue(json.startsWith("{\"outer\""));
        assertTrue(json.endsWith("]}"));
        assertTrue(json.contains("brace } and semicolon }; stay inside string"));
    }

    @Test
    void returnsNullWhenInitialStateIsMissingOrIncomplete() {
        var provider = new SogouImageSearchProvider(null, new WebCapabilityProperties(), JsonMapper.builder().build());
        assertEquals(null, provider.extractInitialStateJson("<html></html>"));
        assertEquals(null, provider.extractInitialStateJson("window.__INITIAL_STATE__ = {\"a\":{\"b\":1}"));
    }
}
