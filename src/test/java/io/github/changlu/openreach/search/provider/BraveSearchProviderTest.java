package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.routing.SearchRouteResolver;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BraveSearchProviderTest {
    @Test
    void parsesCurrentBraveSnippetShape() {
        String html = """
                <main>
                  <div class="snippet fdb">
                    <a href="https://spring.io/projects/spring-boot">
                      <div class="title">Spring Boot</div>
                    </a>
                    <div class="content">Spring Boot helps create stand-alone applications.</div>
                  </div>
                </main>
                """;
        WebCapabilityProperties props = new WebCapabilityProperties();
        var provider = new BraveSearchProvider(null, props, new SearchRouteResolver(props));
        var items = provider.parseResults(Jsoup.parse(html), 5);
        assertEquals(1, items.size());
        assertEquals("Spring Boot", items.get(0).title());
        assertEquals("https://spring.io/projects/spring-boot", items.get(0).url());
        assertEquals("brave", items.get(0).source());
        assertTrue(items.get(0).snippet().contains("stand-alone"));
    }

    @Test
    void ignoresBraveInternalAndMalformedResults() {
        String html = """
                <div class="snippet "><a href="/settings"><div class="title">Settings</div></a></div>
                <div class="snippet "><a href="javascript:void(0)"><div class="title">Bad</div></a></div>
                """;
        WebCapabilityProperties props = new WebCapabilityProperties();
        var provider = new BraveSearchProvider(null, props, new SearchRouteResolver(props));
        assertEquals(0, provider.parseResults(Jsoup.parse(html), 5).size());
    }

    @Test
    void mapsNormalizedTimeRangeToBraveWebFilter() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        var provider = new BraveSearchProvider(null, props, new SearchRouteResolver(props));
        assertEquals("", provider.braveTimeFilter(io.github.changlu.openreach.search.SearchTimeRange.ANY));
        assertEquals("pd", provider.braveTimeFilter(io.github.changlu.openreach.search.SearchTimeRange.DAY));
        assertEquals("pw", provider.braveTimeFilter(io.github.changlu.openreach.search.SearchTimeRange.WEEK));
        assertEquals("pm", provider.braveTimeFilter(io.github.changlu.openreach.search.SearchTimeRange.MONTH));
        assertEquals("py", provider.braveTimeFilter(io.github.changlu.openreach.search.SearchTimeRange.YEAR));
    }
}
