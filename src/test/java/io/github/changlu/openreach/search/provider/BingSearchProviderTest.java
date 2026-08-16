package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.routing.SearchRouteResolver;
import io.github.changlu.openreach.search.SearchTimeRange;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BingSearchProviderTest {
    @Test
    void parsesOrganicResult() {
        String html = """
                <ol id="b_results"><li class="b_algo"><h2><a href="https://spring.io/projects/spring-boot">Spring Boot</a></h2>
                <div class="b_caption"><p>Spring Boot makes it easy to create stand-alone applications.</p></div></li></ol>
                """;
        WebCapabilityProperties props = new WebCapabilityProperties();
        var provider = new BingSearchProvider(null, props, new SearchRouteResolver(props));
        var items = provider.parseResults(Jsoup.parse(html), 5);
        assertEquals(1, items.size());
        assertEquals("Spring Boot", items.get(0).title());
        assertEquals("https://spring.io/projects/spring-boot", items.get(0).url());
        assertEquals("bing", items.get(0).source());
    }

    @Test
    void routesCnAndGlobalToDifferentBingHostsWithoutTimeRange() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        var provider = new BingSearchProvider(null, props, new SearchRouteResolver(props));

        var cn = provider.buildUri("OpenReach", "CN");
        var us = provider.buildUri("OpenReach", "US");

        assertEquals("cn.bing.com", cn.getHost());
        assertEquals("www.bing.com", us.getHost());
        assertTrue(cn.getQuery().contains("cc=CN"));
        assertTrue(us.getQuery().contains("cc=US"));
    }

    @Test
    void supportsVerifiedFreeWebRangesOnly() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        var provider = new BingSearchProvider(null, props, new SearchRouteResolver(props));

        assertTrue(provider.supportsTimeRange(SearchTimeRange.DAY));
        assertTrue(provider.supportsTimeRange(SearchTimeRange.WEEK));
        assertTrue(provider.supportsTimeRange(SearchTimeRange.MONTH));
        assertFalse(provider.supportsTimeRange(SearchTimeRange.YEAR));
    }

    @Test
    void mapsDayWeekMonthToBingWebDateFilters() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        var provider = new BingSearchProvider(null, props, new SearchRouteResolver(props));

        assertEquals("ex1:\"ez1\"", decodedParam(provider.buildUri("AI funding", "CN", SearchTimeRange.DAY), "filters"));
        assertEquals("ex1:\"ez2\"", decodedParam(provider.buildUri("AI funding", "CN", SearchTimeRange.WEEK), "filters"));
        assertEquals("ex1:\"ez3\"", decodedParam(provider.buildUri("AI funding", "CN", SearchTimeRange.MONTH), "filters"));
    }

    @Test
    void restrictedBingSearchUsesInternationalHostBecauseCnDateFilterIsNotReliable() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        var provider = new BingSearchProvider(null, props, new SearchRouteResolver(props));

        var uri = provider.buildUri("AI 融资", "CN", SearchTimeRange.DAY);
        assertEquals("www.bing.com", uri.getHost());
        assertEquals("CN", decodedParam(uri, "cc"));
    }

    @Test
    void rejectsUnverifiedYearFilterInsteadOfSilentlyIgnoringIt() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        var provider = new BingSearchProvider(null, props, new SearchRouteResolver(props));

        assertThrows(BadRequestException.class,
                () -> provider.buildUri("AI funding", "CN", SearchTimeRange.YEAR));
    }

    private String decodedParam(java.net.URI uri, String key) {
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && parts[0].equals(key)) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
