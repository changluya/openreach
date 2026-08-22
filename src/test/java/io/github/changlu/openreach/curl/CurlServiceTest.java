package io.github.changlu.openreach.curl;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.curl.dto.CurlRequest;
import io.github.changlu.openreach.security.UrlSafetyGuard;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurlServiceTest {

    @Test
    void readsGithubLikeJsonAndExposesRateLimitHeaders() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        CurlService service = new CurlService(passThroughGuard(props), props, request -> {
            captured.set(request);
            return response(request, 200,
                    Map.of("content-type", List.of("application/vnd.github+json; charset=utf-8"),
                            "x-ratelimit-remaining", List.of("59")),
                    "{\"full_name\":\"owner/repo\"}");
        });

        var result = service.execute(new CurlRequest(
                "https://api.github.com/repos/owner/repo", null,
                Map.of("Accept", "application/vnd.github+json", "X-GitHub-Api-Version", "2022-11-28"),
                10000), null);

        assertEquals("GET", captured.get().method());
        assertEquals("application/vnd.github+json", captured.get().headers().firstValue("Accept").orElseThrow());
        assertEquals(200, result.statusCode());
        assertTrue(result.body().contains("owner/repo"));
        assertEquals(List.of("59"), result.headers().get("x-ratelimit-remaining"));
    }

    @Test
    void revalidatesEveryRedirectAndStopsWhenRedirectTargetsSelf() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        AtomicInteger validations = new AtomicInteger();
        CurlTargetGuard guard = new CurlTargetGuard(new UrlSafetyGuard(), props) {
            @Override
            public URI validate(String rawUrl, SelfTargetContext self) {
                validations.incrementAndGet();
                URI uri = URI.create(rawUrl);
                if ("openreach.example.com".equals(uri.getHost())) {
                    throw new BadRequestException("Curl target resolves to OpenReach itself; self requests are forbidden");
                }
                return uri;
            }
        };
        CurlService service = new CurlService(guard, props, request -> response(request, 302,
                Map.of("content-type", List.of("text/plain"), "location", List.of("https://openreach.example.com/api/web/search")), "redirect"));

        assertThrows(BadRequestException.class, () -> service.execute(
                new CurlRequest("https://example.com/start", "GET", Map.of(), 10000),
                new SelfTargetContext("openreach.example.com", "openreach", "172.18.0.2", "openreach.example.com")));
        assertEquals(2, validations.get());
    }

    @Test
    void rejectsCredentialAndProxySpoofingHeaders() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        CurlService service = new CurlService(passThroughGuard(props), props,
                request -> response(request, 200, Map.of("content-type", List.of("text/plain")), "ok"));

        assertThrows(BadRequestException.class, () -> service.execute(
                new CurlRequest("https://example.com/", "GET", Map.of("Authorization", "Bearer secret"), 10000), null));
        assertThrows(BadRequestException.class, () -> service.execute(
                new CurlRequest("https://example.com/", "GET", Map.of("Host", "127.0.0.1"), 10000), null));
        assertThrows(BadRequestException.class, () -> service.execute(
                new CurlRequest("https://example.com/", "GET", Map.of("X-Forwarded-Host", "localhost"), 10000), null));
        assertThrows(BadRequestException.class, () -> service.execute(
                new CurlRequest("https://example.com/", "GET", Map.of("X-Api-Key", "secret"), 10000), null));
    }

    @Test
    void serviceRejectsWriteMethodsEvenIfCalledOutsideMvcValidation() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        CurlService service = new CurlService(passThroughGuard(props), props,
                request -> response(request, 200, Map.of("content-type", List.of("text/plain")), "ok"));

        BadRequestException ex = assertThrows(BadRequestException.class, () -> service.execute(
                new CurlRequest("https://example.com/", "POST", Map.of(), 10000), null));
        assertTrue(ex.getMessage().contains("GET/HEAD"));
    }

    @Test
    void rejectsBinaryResponsesAndTruncatesLargeText() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        CurlService binary = new CurlService(passThroughGuard(props), props,
                request -> response(request, 200, Map.of("content-type", List.of("application/zip")), "ZIP"));
        assertThrows(BadRequestException.class, () -> binary.execute(
                new CurlRequest("https://example.com/a.zip", "GET", Map.of(), 10000), null));

        String text = "a".repeat(3000);
        CurlService textual = new CurlService(passThroughGuard(props), props,
                request -> response(request, 200, Map.of("content-type", List.of("text/plain; charset=utf-8")), text));
        var result = textual.execute(new CurlRequest("https://example.com/source.java", "GET", Map.of(), 1000), null);
        assertEquals(1000, result.body().length());
        assertTrue(result.truncated());
    }

    private CurlTargetGuard passThroughGuard(WebCapabilityProperties props) {
        return new CurlTargetGuard(new UrlSafetyGuard(), props) {
            @Override
            public URI validate(String rawUrl, SelfTargetContext self) {
                return URI.create(rawUrl);
            }
        };
    }

    private HttpResponse<InputStream> response(HttpRequest request, int status, Map<String, List<String>> rawHeaders, String body) {
        HttpHeaders headers = HttpHeaders.of(rawHeaders, (name, value) -> true);
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
