package io.github.changlu.openreach.imagesearch;

import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchItem;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageSearchServiceTest {

    @Test
    void autoFallsBackAndAggregates() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getImageSearch().setProviderOrder(List.of("bing", "baidu"));
        ImageSearchService service = new ImageSearchService(List.of(
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
        props.getImageSearch().setProviderOrder(List.of("bing", "baidu"));
        ImageSearchService service = new ImageSearchService(List.of(
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
        ImageSearchService service = new ImageSearchService(List.of(
                provider("bing", true, List.of()),
                provider("baidu", false, List.of(item("https://img.example/a.jpg", "baidu")))
        ), props);
        assertThrows(UpstreamException.class,
                () -> service.search(new ImageSearchRequest("猫", 5, "CN", "bing")));
    }

    @Test
    void autoFailsWhenAllProvidersFail() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getImageSearch().setProviderOrder(List.of("bing", "baidu"));
        ImageSearchService service = new ImageSearchService(List.of(
                provider("bing", true, List.of()),
                provider("baidu", true, List.of())
        ), props);
        assertThrows(UpstreamException.class,
                () -> service.search(new ImageSearchRequest("猫", 5, "CN", null)));
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

    private ImageSearchItem item(String imageUrl, String source) {
        return new ImageSearchItem(1, "title", imageUrl, null, "https://example.com/page", source, source,
                "example.com", 100, 80, "jpg", null, null);
    }
}
