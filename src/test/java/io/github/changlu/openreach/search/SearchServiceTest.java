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
    void allRestrictedTimeRangesRecoverFromLegacyCnChainAndDuckDuckGoChallenge() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setBotChallengeCooldownMs(0);
        // Reproduce the user's stale/legacy chain exactly: unsupported providers first,
        // DuckDuckGo as the only configured time-aware provider, Brave omitted.
        props.getSearch().setCnTimeRangeProviderOrder(List.of(
                "bing", "baidu", "sogou", "so360", "duckduckgo"));

        List<String> calls = new ArrayList<>();
        SearchProvider duckduckgo = new SearchProvider() {
            @Override public String name() { return "duckduckgo"; }
            @Override public boolean supportsTimeRange() { return true; }
            @Override public List<SearchItem> search(String query, int limit, String region) { return List.of(); }
            @Override public List<SearchItem> search(String query, int limit, String region, SearchTimeRange timeRange) {
                calls.add("duckduckgo:" + timeRange.apiValue());
                throw new UpstreamException("duckduckgo bot challenge detected");
            }
        };
        SearchProvider brave = timeAwareProvider("brave", calls);
        SearchService service = service(List.of(
                recordingProvider("bing", new ArrayList<>()),
                recordingProvider("baidu", new ArrayList<>()),
                recordingProvider("sogou", new ArrayList<>()),
                recordingProvider("so360", new ArrayList<>()),
                duckduckgo, brave
        ), props);

        for (String range : List.of("day", "week", "month", "year")) {
            calls.clear();
            var response = service.search(new SearchRequest("AI 融资 投资 最新", 10, null, null, range));
            assertEquals(range, response.timeRange());
            assertEquals(1, response.count());
            assertEquals("brave", response.items().get(0).source());
            assertEquals(List.of("duckduckgo:" + range, "brave:" + range), calls);
        }
    }

    @Test
    void emptyRestrictedCnOrderUsesTimeAwareDefaultsInsteadOfLegacyProviderChain() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setCnProviderOrder(List.of("bing", "baidu", "sogou", "so360", "duckduckgo"));
        props.getSearch().setCnTimeRangeProviderOrder(List.of());
        List<String> calls = new ArrayList<>();
        SearchService service = service(List.of(timeAwareProvider("brave", calls)), props);

        var response = service.search(new SearchRequest("news", 1, "CN", null, "day"));

        assertEquals(List.of("brave:day"), calls);
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
    void rangeAwareCapabilitySkipsBingForYearButKeepsItForMonth() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setGlobalTimeRangeProviderOrder(List.of("bing", "baidu"));
        List<String> calls = new ArrayList<>();

        SearchProvider bing = new SearchProvider() {
            @Override public String name() { return "bing"; }
            @Override public boolean supportsTimeRange() { return true; }
            @Override public boolean supportsTimeRange(SearchTimeRange timeRange) {
                return timeRange == null || timeRange == SearchTimeRange.ANY
                        || timeRange == SearchTimeRange.DAY
                        || timeRange == SearchTimeRange.WEEK
                        || timeRange == SearchTimeRange.MONTH;
            }
            @Override public List<SearchItem> search(String query, int limit, String region) { return List.of(); }
            @Override public List<SearchItem> search(String query, int limit, String region, SearchTimeRange timeRange) {
                calls.add("bing:" + timeRange.apiValue());
                return List.of(new SearchItem(1, "bing", "https://bing.example/result", "", "bing"));
            }
        };
        SearchProvider baidu = timeAwareProvider("baidu", calls);
        SearchService service = service(List.of(bing, baidu), props);

        var month = service.search(new SearchRequest("AI news", 1, "US", null, "month"));
        assertEquals(List.of("bing:month"), calls);
        assertEquals("bing", month.items().get(0).source());

        calls.clear();
        var year = service.search(new SearchRequest("AI news", 1, "US", null, "year"));
        assertEquals(List.of("baidu:year"), calls);
        assertEquals("baidu", year.items().get(0).source());
    }

    @Test
    void legacyDuckDuckGoBraveOnlyCnWeekChainIsAutoExpandedToBaiduAndBing() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setCnTimeRangeProviderOrder(List.of("duckduckgo", "brave"));
        List<String> calls = new ArrayList<>();

        SearchProvider baidu = failingTimeAwareProvider("baidu", "baidu bot challenge detected", calls);
        SearchProvider bing = timeAwareProvider("bing", calls);
        SearchProvider duckduckgo = failingTimeAwareProvider("duckduckgo", "duckduckgo bot challenge detected", calls);
        SearchProvider brave = failingTimeAwareProvider("brave", "brave returned HTTP 429", calls);
        SearchService service = service(List.of(baidu, bing, duckduckgo, brave), props);

        var response = service.search(new SearchRequest(
                "OpenAI 谷歌 最新消息 人工智能 2026年8月", 15, null, null, "week"));

        // limit=15 keeps aggregating after Bing returns one result; DDG/Brave may still fail,
        // but a non-empty Bing result must make the overall request succeed instead of 502.
        assertEquals(List.of("baidu:week", "bing:week", "duckduckgo:week", "brave:week"), calls);
        assertEquals("bing", response.items().get(0).source());
    }

    @Test
    void globalDayStillFallsBackToBaiduWhenBingBraveAndDuckDuckGoFail() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setGlobalTimeRangeProviderOrder(List.of("duckduckgo", "brave"));
        List<String> calls = new ArrayList<>();

        SearchProvider bing = failingTimeAwareProvider("bing", "bing simulated failure", calls);
        SearchProvider brave = failingTimeAwareProvider("brave", "brave returned HTTP 429", calls);
        SearchProvider duckduckgo = failingTimeAwareProvider("duckduckgo", "duckduckgo bot challenge detected", calls);
        SearchProvider baidu = timeAwareProvider("baidu", calls);
        SearchService service = service(List.of(bing, brave, duckduckgo, baidu), props);

        var response = service.search(new SearchRequest(
                "OpenAI news August 15 2026 announcement", 10, "GLOBAL", null, "day"));

        assertEquals(List.of("bing:day", "brave:day", "duckduckgo:day", "baidu:day"), calls);
        assertEquals("baidu", response.items().get(0).source());
    }

    @Test
    void rateLimitedProviderEntersCooldownAndIsSkippedOnNextRequest() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setGlobalTimeRangeProviderOrder(List.of("brave", "duckduckgo"));
        props.getSearch().setRateLimitCooldownMs(60_000);
        List<String> calls = new ArrayList<>();

        SearchProvider brave = failingTimeAwareProvider("brave", "brave returned HTTP 429", calls);
        SearchProvider duckduckgo = timeAwareProvider("duckduckgo", calls);
        SearchService service = service(List.of(brave, duckduckgo), props);

        service.search(new SearchRequest("AI news", 1, "GLOBAL", null, "day"));
        assertEquals(List.of("brave:day", "duckduckgo:day"), calls);

        calls.clear();
        service.search(new SearchRequest("AI news 2", 1, "GLOBAL", null, "day"));
        assertEquals(List.of("duckduckgo:day"), calls);
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

    private SearchProvider failingTimeAwareProvider(String name, String message, List<String> calls) {
        return new SearchProvider() {
            @Override public String name() { return name; }
            @Override public boolean supportsTimeRange() { return true; }
            @Override public List<SearchItem> search(String query, int limit, String region) { return List.of(); }
            @Override public List<SearchItem> search(String query, int limit, String region, SearchTimeRange timeRange) {
                calls.add(name + ":" + timeRange.apiValue());
                throw new UpstreamException(message);
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
