package io.github.changlu.openreach.read.reader;

import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.security.UrlSafetyGuard;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeHttpFetcherTest {

    @Test
    void retriesOneTransientConnectTimeoutAndReturnsSecondResponse() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getRead().setMaxAttempts(2);
        props.getRead().setRetryBackoffMs(0);
        AtomicInteger calls = new AtomicInteger();

        SafeHttpFetcher.HttpSender sender = request -> {
            if (calls.incrementAndGet() == 1) {
                throw new HttpConnectTimeoutException("HTTP connect timed out");
            }
            return response(request, 200, "text/html; charset=UTF-8", "<html><body>qbitai ok</body></html>");
        };

        SafeHttpFetcher fetcher = new SafeHttpFetcher(passThroughGuard(), props, sender);
        var page = fetcher.fetch("https://www.qbitai.com/2026/08/473866.html");

        assertEquals(2, calls.get());
        assertEquals("https://www.qbitai.com/2026/08/473866.html", page.finalUrl());
        assertTrue(new String(page.body(), StandardCharsets.UTF_8).contains("qbitai ok"));
    }

    @Test
    void doesNotBlindlyRetryHttpStatusFailures() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getRead().setMaxAttempts(2);
        props.getRead().setRetryBackoffMs(0);
        AtomicInteger calls = new AtomicInteger();

        SafeHttpFetcher.HttpSender sender = request -> {
            calls.incrementAndGet();
            return response(request, 403, "text/html", "forbidden");
        };

        SafeHttpFetcher fetcher = new SafeHttpFetcher(passThroughGuard(), props, sender);
        UpstreamException ex = assertThrows(UpstreamException.class,
                () -> fetcher.fetch("https://example.com/protected"));

        assertEquals(1, calls.get());
        assertTrue(ex.getMessage().contains("HTTP 403"));
    }

    private UrlSafetyGuard passThroughGuard() {
        return new UrlSafetyGuard() {
            @Override
            public URI validate(String rawUrl) {
                return URI.create(rawUrl);
            }
        };
    }

    private HttpResponse<InputStream> response(HttpRequest request, int status, String contentType, String body) {
        HttpHeaders headers = HttpHeaders.of(
                Map.of("content-type", List.of(contentType)),
                (name, value) -> true);
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
