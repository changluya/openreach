package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SogouSearchProviderTest {
    @Test
    void parsesOrganicResult() {
        String html = """
                <div class="vrwrap"><h3 class="vr-title"><a href="/link?url=demo">Spring Boot 教程</a></h3>
                <div class="str_info">Spring Boot 入门和实践。</div></div>
                """;
        var provider = new SogouSearchProvider(null, new WebCapabilityProperties());
        var items = provider.parseResults(Jsoup.parse(html), 5);
        assertEquals(1, items.size());
        assertTrue(items.get(0).url().startsWith("https://www.sogou.com/link"));
        assertEquals("sogou", items.get(0).source());
    }
}
