package io.github.changlu.openreach.search;

import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.search.dto.SearchItem;
import io.github.changlu.openreach.search.dto.SearchRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchServiceTest {

    @Test
    void autoFallsBackWhenFirstProviderFails() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setProviderOrder(List.of("bing", "baidu"));

        SearchProvider failed = provider("bing", true, List.of());
        SearchProvider ok = provider("baidu", false, List.of(
                new SearchItem(1, "Baidu Result", "https://example.com/a", "snippet", "baidu")
        ));

        SearchService service = new SearchService(List.of(failed, ok), props);
        var response = service.search(new SearchRequest("test", 5, "CN", null));

        assertEquals("auto", response.provider());
        assertEquals(1, response.count());
        assertEquals("baidu", response.items().get(0).source());
    }

    @Test
    void autoDeduplicatesSameUrlAcrossProviders() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setProviderOrder(List.of("bing", "baidu"));

        SearchProvider bing = provider("bing", false, List.of(
                new SearchItem(1, "A", "https://example.com/doc/", "one", "bing")
        ));
        SearchProvider baidu = provider("baidu", false, List.of(
                new SearchItem(1, "A2", "https://example.com/doc", "two", "baidu"),
                new SearchItem(2, "B", "https://example.com/b", "three", "baidu")
        ));

        SearchService service = new SearchService(List.of(bing, baidu), props);
        var response = service.search(new SearchRequest("test", 5, "CN", null));
        assertEquals(2, response.count());
        assertEquals(1, response.items().get(0).rank());
        assertEquals(2, response.items().get(1).rank());
    }

    @Test
    void explicitProviderDoesNotSilentlyFallback() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        SearchProvider failed = provider("bing", true, List.of());
        SearchProvider ok = provider("baidu", false, List.of(new SearchItem(1, "ok", "https://example.com", "", "baidu")));
        SearchService service = new SearchService(List.of(failed, ok), props);
        assertThrows(UpstreamException.class,
                () -> service.search(new SearchRequest("test", 5, "CN", "bing")));
    }

    @Test
    void autoFailsOnlyWhenEveryProviderFails() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setProviderOrder(List.of("bing", "baidu"));
        SearchService service = new SearchService(List.of(
                provider("bing", true, List.of()),
                provider("baidu", true, List.of())
        ), props);
        assertThrows(UpstreamException.class,
                () -> service.search(new SearchRequest("test", 5, "CN", null)));
    }

    private SearchProvider provider(String name, boolean fail, List<SearchItem> items) {
        return new SearchProvider() {
            @Override public String name() { return name; }
            @Override public List<SearchItem> search(String query, int limit, String region) {
                if (fail) throw new UpstreamException(name + " simulated failure");
                return items;
            }
        };
    }
}
