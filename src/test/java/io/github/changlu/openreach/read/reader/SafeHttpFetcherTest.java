package io.github.changlu.openreach.read.reader;

import io.github.changlu.openreach.common.UpstreamException;
import io.github.changlu.openreach.common.UpstreamHttpException;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    void upgradesLegacyHttpBaiduRedirectorBeforeFetching() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getRead().setMaxAttempts(1);
        AtomicReference<URI> requested = new AtomicReference<>();

        SafeHttpFetcher.HttpSender sender = request -> {
            requested.set(request.uri());
            return response(request, 200, "text/html", "<html><body>ok</body></html>");
        };

        SafeHttpFetcher fetcher = new SafeHttpFetcher(passThroughGuard(), props, sender);
        var page = fetcher.fetch("http://www.baidu.com/link?url=opaque");

        assertEquals("https", requested.get().getScheme());
        assertEquals("www.baidu.com", requested.get().getHost());
        assertEquals("https://www.baidu.com/link?url=opaque", page.finalUrl());
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


    @Test
    void retriesTransient521OnceThenReturnsSecondResponse() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getRead().setMaxAttempts(2);
        props.getRead().setRetryBackoffMs(0);
        AtomicInteger calls = new AtomicInteger();

        SafeHttpFetcher.HttpSender sender = request -> {
            if (calls.incrementAndGet() == 1) {
                return response(request, 521, "text/html", "origin down");
            }
            return response(request, 200, "text/html", "<html><body>recovered</body></html>");
        };

        SafeHttpFetcher fetcher = new SafeHttpFetcher(passThroughGuard(), props, sender);
        var page = fetcher.fetch("https://blog.csdn.net/example/article/details/1");

        assertEquals(2, calls.get());
        assertTrue(new String(page.body(), StandardCharsets.UTF_8).contains("recovered"));
    }

    @Test
    void doesNotRetry412AndExposesStructuredNonRetryableStatus() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getRead().setMaxAttempts(2);
        props.getRead().setRetryBackoffMs(0);
        AtomicInteger calls = new AtomicInteger();

        SafeHttpFetcher.HttpSender sender = request -> {
            calls.incrementAndGet();
            return response(request, 412, "text/html", "precondition failed");
        };

        SafeHttpFetcher fetcher = new SafeHttpFetcher(passThroughGuard(), props, sender);
        UpstreamHttpException ex = assertThrows(UpstreamHttpException.class,
                () -> fetcher.fetch("https://www.nhc.gov.cn/example.shtml"));

        assertEquals(1, calls.get());
        assertEquals(412, ex.getStatusCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void sendsBrowserNavigationCompatibilityHeadersWithoutCookiesOrCredentials() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getRead().setMaxAttempts(1);
        AtomicReference<HttpRequest> captured = new AtomicReference<>();

        SafeHttpFetcher.HttpSender sender = request -> {
            captured.set(request);
            return response(request, 200, "text/html", "<html><body>ok</body></html>");
        };

        SafeHttpFetcher fetcher = new SafeHttpFetcher(passThroughGuard(), props, sender);
        fetcher.fetch("https://example.com/article");

        HttpRequest request = captured.get();
        assertEquals("navigate", request.headers().firstValue("Sec-Fetch-Mode").orElseThrow());
        assertEquals("document", request.headers().firstValue("Sec-Fetch-Dest").orElseThrow());
        assertEquals("1", request.headers().firstValue("Upgrade-Insecure-Requests").orElseThrow());
        assertTrue(request.headers().firstValue("Cookie").isEmpty());
        assertTrue(request.headers().firstValue("Authorization").isEmpty());
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
