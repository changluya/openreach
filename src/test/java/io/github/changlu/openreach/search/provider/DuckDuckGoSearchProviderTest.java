package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.routing.SearchRouteResolver;
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
        WebCapabilityProperties props = new WebCapabilityProperties();
        var provider = new DuckDuckGoSearchProvider(null, props, new SearchRouteResolver(props));
        var items = provider.parseResults(Jsoup.parse(html), 5);
        assertEquals(1, items.size());
        assertEquals("https://example.com/doc", items.get(0).url());
        assertEquals("duckduckgo", items.get(0).source());
    }

    @Test
    void detectsBotChallengePage() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        var provider = new DuckDuckGoSearchProvider(null, props, new SearchRouteResolver(props));
        assertEquals(true, provider.isCaptcha(Jsoup.parse("<form id=\"challenge-form\">not a robot</form>")));
    }

    @Test
    void mapsNormalizedTimeRangeToDuckDuckGoNoJsFilter() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        var provider = new DuckDuckGoSearchProvider(null, props, new SearchRouteResolver(props));
        assertEquals("", provider.duckDuckGoTimeFilter(io.github.changlu.openreach.search.SearchTimeRange.ANY));
        assertEquals("d", provider.duckDuckGoTimeFilter(io.github.changlu.openreach.search.SearchTimeRange.DAY));
        assertEquals("w", provider.duckDuckGoTimeFilter(io.github.changlu.openreach.search.SearchTimeRange.WEEK));
        assertEquals("m", provider.duckDuckGoTimeFilter(io.github.changlu.openreach.search.SearchTimeRange.MONTH));
        assertEquals("y", provider.duckDuckGoTimeFilter(io.github.changlu.openreach.search.SearchTimeRange.YEAR));
    }
}
