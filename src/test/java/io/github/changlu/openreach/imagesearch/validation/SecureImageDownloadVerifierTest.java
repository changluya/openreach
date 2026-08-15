package io.github.changlu.openreach.imagesearch.validation;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.security.UrlSafetyGuard;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.net.URI;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecureImageDownloadVerifierTest {

    @Test
    void acceptsCommonPassiveImageSignatures() {
        assertTrue(SecureImageDownloadVerifier.hasPassiveImageSignature(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}));
        assertTrue(SecureImageDownloadVerifier.hasPassiveImageSignature(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}));
        assertTrue(SecureImageDownloadVerifier.hasPassiveImageSignature("GIF89a......".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(SecureImageDownloadVerifier.hasPassiveImageSignature("RIFF0000WEBP".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(SecureImageDownloadVerifier.hasPassiveImageSignature(new byte[]{'B', 'M', 0, 0}));
        assertTrue(SecureImageDownloadVerifier.hasPassiveImageSignature(new byte[]{'I', 'I', 42, 0}));
        assertTrue(SecureImageDownloadVerifier.hasPassiveImageSignature(new byte[]{0, 0, 1, 0}));
        assertTrue(SecureImageDownloadVerifier.hasPassiveImageSignature(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'a', 'v', 'i', 'f'}));
    }

    @Test
    void rejectsHtmlSvgTextAndRandomBytesBySignature() {
        assertFalse(SecureImageDownloadVerifier.hasPassiveImageSignature("<html><body>blocked</body></html>".getBytes(StandardCharsets.UTF_8)));
        assertFalse(SecureImageDownloadVerifier.hasPassiveImageSignature("<svg xmlns='http://www.w3.org/2000/svg'></svg>".getBytes(StandardCharsets.UTF_8)));
        assertFalse(SecureImageDownloadVerifier.hasPassiveImageSignature("plain text".getBytes(StandardCharsets.UTF_8)));
        assertFalse(SecureImageDownloadVerifier.hasPassiveImageSignature(new byte[]{1, 2, 3, 4, 5}));
        assertFalse(SecureImageDownloadVerifier.hasPassiveImageSignature(new byte[0]));
        assertFalse(SecureImageDownloadVerifier.hasPassiveImageSignature(null));
    }

    @Test
    void probesRealHttpStatusContentAndRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
        server.createContext("/image", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
        });
        server.createContext("/html", exchange -> {
            byte[] html = "<html>blocked</html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        });
        server.createContext("/gone", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/image");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        WebCapabilityProperties props = new WebCapabilityProperties();
        SecureImageDownloadVerifier verifier = new SecureImageDownloadVerifier(new TestUrlSafetyGuard(), props);
        try {
            assertTrue(verifier.isDownloadable("http://127.0.0.1:" + port + "/image"));
            assertTrue(verifier.isDownloadable("http://127.0.0.1:" + port + "/redirect"));
            assertFalse(verifier.isDownloadable("http://127.0.0.1:" + port + "/html"));
            assertFalse(verifier.isDownloadable("http://127.0.0.1:" + port + "/gone"));
        } finally {
            verifier.close();
            server.stop(0);
        }
    }

    private static final class TestUrlSafetyGuard extends UrlSafetyGuard {
        @Override
        public URI validate(String rawUrl) {
            return URI.create(rawUrl);
        }
    }

    @Test
    void rejectsLocalImageUrlsBeforeNetworkProbe() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        SecureImageDownloadVerifier verifier = new SecureImageDownloadVerifier(new UrlSafetyGuard(props), props);
        try {
            assertFalse(verifier.isDownloadable("http://127.0.0.1/private.jpg"));
            assertFalse(verifier.isDownloadable("http://169.254.169.254/latest/meta-data/image.jpg"));
            assertFalse(verifier.isDownloadable("http://localhost/image.png"));
        } finally {
            verifier.close();
        }
    }
}
