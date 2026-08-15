package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DuckDuckGoSearchProviderTest {
    @Test
    void parsesTypicalHtmlResultAndDecodesTargetUrl() {
        String html = """
                <div class="result">
                  <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fdoc">Example Doc</a>
                  <a class="result__snippet">Useful snippet</a>
                </div>
                """;
        var provider = new DuckDuckGoSearchProvider(null, new WebCapabilityProperties());
        var items = provider.parseResults(Jsoup.parse(html), 5);
        assertEquals(1, items.size());
        assertEquals("https://example.com/doc", items.get(0).url());
        assertEquals("duckduckgo", items.get(0).source());
    }
}
