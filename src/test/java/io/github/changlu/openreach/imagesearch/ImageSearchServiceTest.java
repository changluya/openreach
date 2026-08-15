package io.github.changlu.openreach.imagesearch;

import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchItem;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchRequest;
import io.github.changlu.openreach.imagesearch.validation.ImageDownloadVerifier;
import io.github.changlu.openreach.routing.SearchRouteResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageSearchServiceTest {

    @Test
    void autoFallsBackAndAggregates() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getImageSearch().setCnProviderOrder(List.of("bing", "baidu"));
        ImageSearchService service = service(List.of(
                provider("bing", true, List.of()),
                provider("baidu", false, List.of(item("https://img.example/a.jpg", "baidu")))
        ), props);

        var response = service.search(new ImageSearchRequest("猫", 5, "CN", null));
        assertEquals("auto", response.provider());
        assertEquals(1, response.count());
        assertEquals("baidu", response.items().get(0).source());
    }

    @Test
    void autoDeduplicatesByImageUrl() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getImageSearch().setCnProviderOrder(List.of("bing", "baidu"));
        ImageSearchService service = service(List.of(
                provider("bing", false, List.of(item("https://img.example/a.jpg", "bing"))),
                provider("baidu", false, List.of(item("https://img.example/a.jpg", "baidu"), item("https://img.example/b.jpg", "baidu")))
        ), props);

        var response = service.search(new ImageSearchRequest("猫", 5, "CN", null));
        assertEquals(2, response.count());
        assertEquals(1, response.items().get(0).rank());
        assertEquals(2, response.items().get(1).rank());
    }

    @Test
    void explicitProviderDoesNotFallback() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        ImageSearchService service = service(List.of(
                provider("bing", true, List.of()),
                provider("baidu", false, List.of(item("https://img.example/a.jpg", "baidu")))
        ), props);
        assertThrows(UpstreamException.class,
                () -> service.search(new ImageSearchRequest("猫", 5, "CN", "bing")));
    }

    @Test
    void autoFailsWhenAllProvidersFail() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getImageSearch().setCnProviderOrder(List.of("bing", "baidu"));
        ImageSearchService service = service(List.of(
                provider("bing", true, List.of()),
                provider("baidu", true, List.of())
        ), props);
        assertThrows(UpstreamException.class,
                () -> service.search(new ImageSearchRequest("猫", 5, "CN", null)));
    }

    @Test
    void globalRegionUsesGlobalImageChain() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getImageSearch().setCnProviderOrder(List.of("baidu"));
        props.getImageSearch().setGlobalProviderOrder(List.of("wikimedia", "openverse"));
        List<String> calls = new ArrayList<>();
        ImageSearchService service = service(List.of(
                recordingProvider("baidu", calls),
                recordingProvider("wikimedia", calls),
                recordingProvider("openverse", calls)
        ), props);

        service.search(new ImageSearchRequest("Golden Gate Bridge", 1, "US", null));
        assertEquals(List.of("wikimedia"), calls);
    }

    @Test
    void autoRegionKeepsCnCompatibility() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getImageSearch().setCnProviderOrder(List.of("baidu"));
        props.getImageSearch().setGlobalProviderOrder(List.of("wikimedia"));
        List<String> calls = new ArrayList<>();
        ImageSearchService service = service(List.of(recordingProvider("baidu", calls), recordingProvider("wikimedia", calls)), props);

        service.search(new ImageSearchRequest("杭州", 1, "auto", null));
        assertEquals(List.of("baidu"), calls);
    }

    @Test
    void legacyProviderOrderStillControlsCnWhenCnOrderMissing() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getImageSearch().setProviderOrder(List.of("legacy"));
        props.getImageSearch().setCnProviderOrder(List.of());
        List<String> calls = new ArrayList<>();
        ImageSearchService service = service(List.of(recordingProvider("legacy", calls)), props);

        service.search(new ImageSearchRequest("杭州", 1, "CN", null));
        assertEquals(List.of("legacy"), calls);
    }

    @Test
    void emptyGlobalImageProviderChainFailsWithClearConfigurationError() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getImageSearch().setGlobalProviderOrder(List.of());
        ImageSearchService service = service(List.of(recordingProvider("wikimedia", new ArrayList<>())), props);

        UpstreamException ex = assertThrows(UpstreamException.class,
                () -> service.search(new ImageSearchRequest("test", 1, "US", null)));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("No free image search providers configured"));
    }

    @Test
    void explicitImageProviderOverridesRegionRouteSelection() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getImageSearch().setGlobalProviderOrder(List.of("wikimedia"));
        List<String> calls = new ArrayList<>();
        ImageSearchService service = service(List.of(
                recordingProvider("baidu", calls),
                recordingProvider("wikimedia", calls)
        ), props);

        service.search(new ImageSearchRequest("test", 1, "US", "baidu"));

        assertEquals(List.of("baidu"), calls);
    }

    @Test
    void autoContinuesWhenImageProviderReturnsNullOrEmptyResults() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getImageSearch().setGlobalProviderOrder(List.of("null-provider", "empty-provider", "wikimedia"));
        List<String> calls = new ArrayList<>();

        ImageSearchProvider nullProvider = new ImageSearchProvider() {
            @Override public String name() { return "null-provider"; }
            @Override public List<ImageSearchItem> search(String query, int limit, String region) {
                calls.add(name());
                return null;
            }
        };
        ImageSearchProvider emptyProvider = new ImageSearchProvider() {
            @Override public String name() { return "empty-provider"; }
            @Override public List<ImageSearchItem> search(String query, int limit, String region) {
                calls.add(name());
                return List.of();
            }
        };

        ImageSearchService service = service(List.of(nullProvider, emptyProvider, recordingProvider("wikimedia", calls)), props);
        var response = service.search(new ImageSearchRequest("test", 1, "US", null));

        assertEquals(List.of("null-provider", "empty-provider", "wikimedia"), calls);
        assertEquals(1, response.count());
        assertEquals("wikimedia", response.items().get(0).source());
    }

    @Test
    void globalImageProviderOrderToleratesWhitespaceAndBlankEntries() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getImageSearch().setGlobalProviderOrder(java.util.Arrays.asList(" ", null, " WIKIMEDIA "));
        List<String> calls = new ArrayList<>();
        ImageSearchService service = service(List.of(recordingProvider("wikimedia", calls)), props);

        service.search(new ImageSearchRequest("test", 1, "US", null));
        assertEquals(List.of("wikimedia"), calls);
    }

    @Test
    void onlyDownloadableImagesAreReturnedAndInvalidHotlinksAreFiltered() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getImageSearch().setCnProviderOrder(List.of("bing"));
        ImageSearchProvider bing = provider("bing", false, List.of(
                item("https://img.example/dead.jpg", "bing"),
                item("https://img.example/good.jpg", "bing"),
                item("https://img.example/html.jpg", "bing")
        ));
        ImageDownloadVerifier verifier = url -> url.endsWith("/good.jpg");
        ImageSearchService service = service(List.of(bing), props, verifier);

        var response = service.search(new ImageSearchRequest("cat", 3, "CN", null));

        assertEquals(1, response.count());
        assertEquals("https://img.example/good.jpg", response.items().get(0).imageUrl());
    }

    @Test
    void autoFallsThroughWhenFirstProviderHasResultsButNoneAreDownloadable() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getImageSearch().setGlobalProviderOrder(List.of("bing", "wikimedia"));
        List<String> calls = new ArrayList<>();
        ImageSearchProvider bing = new ImageSearchProvider() {
            @Override public String name() { return "bing"; }
            @Override public List<ImageSearchItem> search(String query, int limit, String region) {
                calls.add(name());
                return List.of(item("https://img.example/dead.jpg", name()));
            }
        };
        ImageSearchProvider wikimedia = new ImageSearchProvider() {
            @Override public String name() { return "wikimedia"; }
            @Override public List<ImageSearchItem> search(String query, int limit, String region) {
                calls.add(name());
                return List.of(item("https://img.example/good.png", name()));
            }
        };
        ImageSearchService service = service(List.of(bing, wikimedia), props, url -> url.endsWith("good.png"));

        var response = service.search(new ImageSearchRequest("cat", 1, "US", null));

        assertEquals(List.of("bing", "wikimedia"), calls);
        assertEquals(1, response.count());
        assertEquals("wikimedia", response.items().get(0).source());
    }

    @Test
    void explicitProviderFailsWhenSearchResultsAreNotDownloadable() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        ImageSearchService service = service(List.of(
                provider("bing", false, List.of(item("https://img.example/dead.jpg", "bing")))
        ), props, url -> false);

        UpstreamException ex = assertThrows(UpstreamException.class,
                () -> service.search(new ImageSearchRequest("cat", 1, "CN", "bing")));
        assertTrue(ex.getMessage().contains("none are directly downloadable"));
    }

    @Test
    void candidateLimitOverfetchesWithinConfiguredCapForDownloadValidation() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getImageSearch().setDownloadCandidateMultiplier(4);
        props.getImageSearch().setDownloadMaxCandidates(25);
        ImageSearchService service = service(List.of(recordingProvider("bing", new ArrayList<>())), props);

        assertEquals(20, service.candidateLimit(5));
        assertEquals(25, service.candidateLimit(10));
    }

    private ImageSearchService service(List<ImageSearchProvider> providers, WebCapabilityProperties props) {
        return service(providers, props, url -> true);
    }

    private ImageSearchService service(List<ImageSearchProvider> providers, WebCapabilityProperties props, ImageDownloadVerifier verifier) {
        return new ImageSearchService(providers, props, new SearchRouteResolver(props),
                new io.github.changlu.openreach.routing.ProviderChainResolver(props), verifier);
    }

    private ImageSearchProvider provider(String name, boolean fail, List<ImageSearchItem> items) {
        return new ImageSearchProvider() {
            @Override public String name() { return name; }
            @Override public List<ImageSearchItem> search(String query, int limit, String region) {
                if (fail) throw new UpstreamException(name + " simulated failure");
                return items;
            }
        };
    }

    private ImageSearchProvider recordingProvider(String name, List<String> calls) {
        return new ImageSearchProvider() {
            @Override public String name() { return name; }
            @Override public List<ImageSearchItem> search(String query, int limit, String region) {
                calls.add(name);
                return List.of(item("https://img.example/" + name + ".jpg", name));
            }
        };
    }

    private ImageSearchItem item(String imageUrl, String source) {
        return new ImageSearchItem(1, "title", imageUrl, null, "https://example.com/page", source, source,
                "example.com", 100, 80, "jpg", null, null);
    }
}
