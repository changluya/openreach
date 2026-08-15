package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class So360SearchProviderTest {
    @Test
    void parsesOrganicResult() {
        String html = """
                <ul><li class="res-list"><h3><a href="https://example.com/spring">Spring Boot 实战</a></h3>
                <p class="res-desc">Spring Boot 实战内容。</p></li></ul>
                """;
        var provider = new So360SearchProvider(null, new WebCapabilityProperties());
        var items = provider.parseResults(Jsoup.parse(html), 5);
        assertEquals(1, items.size());
        assertEquals("https://example.com/spring", items.get(0).url());
        assertEquals("so360", items.get(0).source());
    }
}
