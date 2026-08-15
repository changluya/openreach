package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaiduSearchProviderTest {
    @Test
    void parsesOrganicResultAndKeepsBaiduRedirect() {
        String html = """
                <div id="content_left">
                  <div class="result c-container"><h3><a href="/link?url=abc123">Spring Boot 中文资料</a></h3>
                  <div class="c-abstract">Spring Boot 相关文档和教程。</div></div>
                </div>
                """;
        var provider = new BaiduSearchProvider(null, new WebCapabilityProperties());
        var items = provider.parseResults(Jsoup.parse(html), 5);
        assertEquals(1, items.size());
        assertEquals("Spring Boot 中文资料", items.get(0).title());
        assertTrue(items.get(0).url().startsWith("https://www.baidu.com/link?url="));
        assertEquals("baidu", items.get(0).source());
    }
}
