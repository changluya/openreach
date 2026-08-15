package io.github.changlu.openreach.imagesearch.provider;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import tools.jackson.databind.json.JsonMapper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BingImageSearchProviderTest {
    @Test
    void parsesIuscMetadata() {
        String html = """
                <ul class="dgControl_list"><li>
                  <a class="iusc" m='{"murl":"https://img.example/cat.jpg","turl":"https://thumb.example/cat.jpg","purl":"https://site.example/cat","t":"Cat","expw":1200,"exph":800}'></a>
                </li></ul>
                """;
        var provider = new BingImageSearchProvider(null, new WebCapabilityProperties(), JsonMapper.builder().build());
        var items = provider.parseResults(Jsoup.parse(html), 5);
        assertEquals(1, items.size());
        assertEquals("bing", items.get(0).provider());
        assertEquals("https://img.example/cat.jpg", items.get(0).imageUrl());
        assertEquals("https://site.example/cat", items.get(0).sourcePageUrl());
        assertEquals(1200, items.get(0).width().intValue());
        assertEquals("site.example", items.get(0).domain());
    }
}
