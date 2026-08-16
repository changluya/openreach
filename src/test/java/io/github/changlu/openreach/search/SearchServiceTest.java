package io.github.changlu.openreach.search;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.routing.SearchRouteResolver;
import io.github.changlu.openreach.search.dto.SearchItem;
import io.github.changlu.openreach.search.dto.SearchRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchServiceTest {

    @Test
    void autoFallsBackWhenFirstProviderFails() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setCnProviderOrder(List.of("bing", "baidu"));

        SearchProvider failed = provider("bing", true, List.of());
        SearchProvider ok = provider("baidu", false, List.of(
                new SearchItem(1, "Baidu Result", "https://example.com/a", "snippet", "baidu")
        ));

        SearchService service = service(List.of(failed, ok), props);
        var response = service.search(new SearchRequest("test", 5, "CN", null, null));

        assertEquals("auto", response.provider());
        assertEquals(1, response.count());
        assertEquals("baidu", response.items().get(0).source());
    }

    @Test
    void autoDeduplicatesSameUrlAcrossProviders() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setCnProviderOrder(List.of("bing", "baidu"));

        SearchProvider bing = provider("bing", false, List.of(
                new SearchItem(1, "A", "https://example.com/doc/", "one", "bing")
        ));
        SearchProvider baidu = provider("baidu", false, List.of(
                new SearchItem(1, "A2", "https://example.com/doc", "two", "baidu"),
                new SearchItem(2, "B", "https://example.com/b", "three", "baidu")
        ));

        SearchService service = service(List.of(bing, baidu), props);
        var response = service.search(new SearchRequest("test", 5, "CN", null, null));
        assertEquals(2, response.count());
        assertEquals(1, response.items().get(0).rank());
        assertEquals(2, response.items().get(1).rank());
    }

    @Test
    void explicitProviderDoesNotSilentlyFallback() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        SearchProvider failed = provider("bing", true, List.of());
        SearchProvider ok = provider("baidu", false, List.of(new SearchItem(1, "ok", "https://example.com", "", "baidu")));
        SearchService service = service(List.of(failed, ok), props);
        assertThrows(UpstreamException.class,
                () -> service.search(new SearchRequest("test", 5, "CN", "bing", null)));
    }

    @Test
    void autoFailsOnlyWhenEveryProviderFails() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setCnProviderOrder(List.of("bing", "baidu"));
        SearchService service = service(List.of(
                provider("bing", true, List.of()),
                provider("baidu", true, List.of())
        ), props);
        assertThrows(UpstreamException.class,
                () -> service.search(new SearchRequest("test", 5, "CN", null, null)));
    }

    @Test
    void cnRegionUsesCnProviderChain() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setCnProviderOrder(List.of("baidu"));
        props.getSearch().setGlobalProviderOrder(List.of("brave"));
        List<String> calls = new ArrayList<>();

        SearchService service = service(List.of(
                recordingProvider("baidu", calls),
                recordingProvider("brave", calls)
        ), props);
        service.search(new SearchRequest("test", 1, "zh-CN", null, null));
        assertEquals(List.of("baidu"), calls);
    }

    @Test
    void nonCnRegionUsesGlobalProviderChain() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setCnProviderOrder(List.of("baidu"));
        props.getSearch().setGlobalProviderOrder(List.of("brave", "duckduckgo", "bing"));
        List<String> calls = new ArrayList<>();

        SearchService service = service(List.of(
                recordingProvider("baidu", calls),
                recordingProvider("brave", calls),
                recordingProvider("duckduckgo", calls),
                recordingProvider("bing", calls)
        ), props);
        service.search(new SearchRequest("test", 1, "US", null, null));
        assertEquals(List.of("brave"), calls);
    }

    @Test
    void autoKeepsV101DefaultCnBehavior() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setCnProviderOrder(List.of("baidu"));
        props.getSearch().setGlobalProviderOrder(List.of("brave"));
        List<String> calls = new ArrayList<>();
        SearchService service = service(List.of(recordingProvider("baidu", calls), recordingProvider("brave", calls)), props);

        service.search(new SearchRequest("test", 1, "auto", null, null));
        assertEquals(List.of("baidu"), calls);
    }

    @Test
    void legacyProviderOrderStillControlsCnWhenNewCnOrderIsAbsent() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setProviderOrder(List.of("legacy"));
        props.getSearch().setCnProviderOrder(List.of());
        List<String> calls = new ArrayList<>();
        SearchService service = service(List.of(recordingProvider("legacy", calls)), props);

        service.search(new SearchRequest("test", 1, "CN", null, null));
        assertEquals(List.of("legacy"), calls);
    }

    @Test
    void emptyGlobalProviderChainFailsWithClearConfigurationError() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setGlobalProviderOrder(List.of());
        SearchService service = service(List.of(recordingProvider("brave", new ArrayList<>())), props);

        UpstreamException ex = assertThrows(UpstreamException.class,
                () -> service.search(new SearchRequest("test", 1, "US", null, null)));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("No free search providers configured"));
    }

    @Test
    void autoContinuesWhenProviderReturnsNullOrEmptyResults() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setGlobalProviderOrder(List.of("null-provider", "empty-provider", "brave"));
        List<String> calls = new ArrayList<>();

        SearchProvider nullProvider = new SearchProvider() {
            @Override public String name() { return "null-provider"; }
            @Override public List<SearchItem> search(String query, int limit, String region) {
                calls.add(name());
                return null;
            }
        };
        SearchProvider emptyProvider = new SearchProvider() {
            @Override public String name() { return "empty-provider"; }
            @Override public List<SearchItem> search(String query, int limit, String region) {
                calls.add(name());
                return List.of();
            }
        };

        SearchService service = service(List.of(nullProvider, emptyProvider, recordingProvider("brave", calls)), props);
        var response = service.search(new SearchRequest("test", 1, "US", null, null));

        assertEquals(List.of("null-provider", "empty-provider", "brave"), calls);
        assertEquals(1, response.count());
        assertEquals("brave", response.items().get(0).source());
    }

    @Test
    void explicitProviderOverridesRegionRouteSelection() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setGlobalProviderOrder(List.of("brave"));
        List<String> calls = new ArrayList<>();
        SearchService service = service(List.of(
                recordingProvider("baidu", calls),
                recordingProvider("brave", calls)
        ), props);

        service.search(new SearchRequest("test", 1, "US", "baidu", null));

        assertEquals(List.of("baidu"), calls);
    }

    @Test
    void providerOrderToleratesWhitespaceAndSkipsBlankEntries() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setGlobalProviderOrder(java.util.Arrays.asList("  ", null, " BRAVE "));
        List<String> calls = new ArrayList<>();
        SearchService service = service(List.of(recordingProvider("brave", calls)), props);

        service.search(new SearchRequest("test", 1, "US", null, null));
        assertEquals(List.of("brave"), calls);
    }

    @Test
    void timeRangeSkipsUnsupportedProvidersAndUsesCapableFallback() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setGlobalTimeRangeProviderOrder(List.of("unsupported", "brave"));
        List<String> calls = new ArrayList<>();
        SearchProvider unsupported = recordingProvider("unsupported", calls);
        SearchProvider brave = timeAwareProvider("brave", calls);
        SearchService service = service(List.of(unsupported, brave), props);

        var response = service.search(new SearchRequest("AI news", 1, "US", null, "week"));

        assertEquals(List.of("brave:week"), calls);
        assertEquals("week", response.timeRange());
        assertEquals(1, response.count());
    }

    @Test
    void explicitProviderRejectsUnsupportedTimeRangeInsteadOfIgnoringIt() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        SearchService service = service(List.of(recordingProvider("baidu", new ArrayList<>())), props);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.search(new SearchRequest("news", 3, "CN", "baidu", "day")));

        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("does not support timeRange"));
    }

    @Test
    void autoTimeRangeReturnsBadRequestWhenNoConfiguredProviderSupportsIt() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setGlobalTimeRangeProviderOrder(List.of("bing", "baidu"));
        SearchService service = service(List.of(
                recordingProvider("bing", new ArrayList<>()),
                recordingProvider("baidu", new ArrayList<>())
        ), props);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.search(new SearchRequest("news", 3, "US", null, "week")));

        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("No configured search provider supports timeRange=week"));
    }

    @Test
    void autoFailureSummaryReportsAttemptedAndSkippedProviders() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setGlobalTimeRangeProviderOrder(List.of("bing", "duckduckgo"));
        SearchProvider failedTimeAware = new SearchProvider() {
            @Override public String name() { return "duckduckgo"; }
            @Override public boolean supportsTimeRange() { return true; }
            @Override public List<SearchItem> search(String query, int limit, String region) { return List.of(); }
            @Override public List<SearchItem> search(String query, int limit, String region, SearchTimeRange timeRange) {
                throw new UpstreamException("duckduckgo bot challenge detected");
            }
        };
        SearchService service = service(List.of(recordingProvider("bing", new ArrayList<>()), failedTimeAware), props);

        UpstreamException ex = assertThrows(UpstreamException.class,
                () -> service.search(new SearchRequest("news", 3, "US", null, "week")));

        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("attempted=1, skipped=1"));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("bot challenge"));
    }

    @Test
    void explicitTimeAwareProviderReceivesNormalizedTimeRange() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        List<String> calls = new ArrayList<>();
        SearchService service = service(List.of(timeAwareProvider("duckduckgo", calls)), props);

        var response = service.search(new SearchRequest("news", 1, "US", "duckduckgo", "qdr:m"));

        assertEquals(List.of("duckduckgo:month"), calls);
        assertEquals("month", response.timeRange());
    }

    private SearchService service(List<SearchProvider> providers, WebCapabilityProperties props) {
        return new SearchService(providers, props, new SearchRouteResolver(props), new io.github.changlu.openreach.routing.ProviderChainResolver(props));
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

    private SearchProvider timeAwareProvider(String name, List<String> calls) {
        return new SearchProvider() {
            @Override public String name() { return name; }
            @Override public boolean supportsTimeRange() { return true; }
            @Override public List<SearchItem> search(String query, int limit, String region) {
                return search(query, limit, region, SearchTimeRange.ANY);
            }
            @Override public List<SearchItem> search(String query, int limit, String region, SearchTimeRange timeRange) {
                calls.add(name + ":" + timeRange.apiValue());
                return List.of(new SearchItem(1, name, "https://" + name + ".example/result", "", name));
            }
        };
    }

    private SearchProvider recordingProvider(String name, List<String> calls) {
        return new SearchProvider() {
            @Override public String name() { return name; }
            @Override public List<SearchItem> search(String query, int limit, String region) {
                calls.add(name);
                return List.of(new SearchItem(1, name, "https://" + name + ".example/result", "", name));
            }
        };
    }
}
