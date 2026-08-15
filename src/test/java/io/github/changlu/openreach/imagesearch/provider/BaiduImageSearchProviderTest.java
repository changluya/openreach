package io.github.changlu.openreach.imagesearch.provider;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaiduImageSearchProviderTest {
    @Test
    void parsesAcjsonImageResult() {
        String json = """
                {"data":[{"thumbURL":"https://thumb.example/a.jpg","fromPageTitle":"<b>杭州西湖</b>","fromURLHost":"example.com","width":1280,"height":720,"type":"jpg","replaceUrl":[{"FromURL":"https://example.com/page","ObjURL":"https://img.example/a.jpg"}]},{}]}
                """;
        var provider = new BaiduImageSearchProvider(null, new WebCapabilityProperties(), JsonMapper.builder().build());
        var items = provider.parseResults(json, 5);
        assertEquals(1, items.size());
        assertEquals("baidu", items.get(0).provider());
        assertEquals("杭州西湖", items.get(0).title());
        assertEquals("https://img.example/a.jpg", items.get(0).imageUrl());
        assertEquals(1280, items.get(0).width().intValue());
    }
}
