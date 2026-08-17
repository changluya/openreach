package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchHttpClientTest {

    @Test
    void productionConstructorIsExplicitlyAutowiredWhenTestConstructorExists() throws Exception {
        var constructor = SearchHttpClient.class.getConstructor(WebCapabilityProperties.class);
        assertTrue(constructor.isAnnotationPresent(Autowired.class));
    }

    @Test
    void followsProvider302AndUpgradesHttpsToHttpLocationBackToHttps() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setMaxRedirects(3);
        List<URI> requests = new ArrayList<>();

        SearchHttpClient.HttpSender sender = request -> {
            requests.add(request.uri());
            if (requests.size() == 1) {
                return response(request, 302, Map.of("location", List.of("http://www.sogou.com/web?query=OpenAI&ie=utf8")), "");
            }
            return response(request, 200, Map.of("content-type", List.of("text/html; charset=UTF-8")),
                    "<html><body><div class='vrwrap'>ok</div></body></html>");
        };

        SearchHttpClient client = new SearchHttpClient(props, sender);
        var doc = client.get("sogou", URI.create("https://www.sogou.com/web?query=OpenAI"));

        assertEquals(2, requests.size());
        assertEquals("https", requests.get(1).getScheme());
        assertEquals("www.sogou.com", requests.get(1).getHost());
        assertTrue(doc.text().contains("ok"));
    }

    @Test
    void followsRelativeRedirectFor360() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        List<URI> requests = new ArrayList<>();

        SearchHttpClient.HttpSender sender = request -> {
            requests.add(request.uri());
            if (requests.size() == 1) {
                return response(request, 302, Map.of("location", List.of("/index.php?ie=utf-8&q=OpenAI")), "");
            }
            return response(request, 200, Map.of("content-type", List.of("text/html")), "<html><body>360 ok</body></html>");
        };

        SearchHttpClient client = new SearchHttpClient(props, sender);
        var doc = client.get("so360", URI.create("https://www.so.com/s?q=OpenAI"));

        assertEquals(2, requests.size());
        assertEquals("/index.php", requests.get(1).getPath());
        assertTrue(doc.text().contains("360 ok"));
    }

    @Test
    void rejectsRedirectOutsideTrustedProviderDomain() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        SearchHttpClient.HttpSender sender = request -> response(request, 302,
                Map.of("location", List.of("https://evil.example/internal")), "");
        SearchHttpClient client = new SearchHttpClient(props, sender);

        UpstreamException ex = assertThrows(UpstreamException.class,
                () -> client.get("sogou", URI.create("https://www.sogou.com/web?query=x")));
        assertTrue(ex.getMessage().contains("outside trusted provider domain"));
    }

    @Test
    void stopsRedirectLoopAtConfiguredLimit() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setMaxRedirects(1);
        SearchHttpClient.HttpSender sender = request -> response(request, 302,
                Map.of("location", List.of("/web?query=loop")), "");
        SearchHttpClient client = new SearchHttpClient(props, sender);

        UpstreamException ex = assertThrows(UpstreamException.class,
                () -> client.get("sogou", URI.create("https://www.sogou.com/web?query=x")));
        assertTrue(ex.getMessage().contains("redirect limit=1"));
    }

    private HttpResponse<InputStream> response(HttpRequest request, int status,
                                               Map<String, List<String>> headersMap, String body) {
        HttpHeaders headers = HttpHeaders.of(headersMap, (name, value) -> true);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return request; }
            @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return headers; }
            @Override public InputStream body() { return new ByteArrayInputStream(bytes); }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return request.uri(); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
