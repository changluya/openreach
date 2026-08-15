package io.github.changlu.openreach.imagesearch.validation;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.imagesearch.dto.ImageSearchItem;
import io.github.changlu.openreach.security.UrlSafetyGuard;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, SSRF-protected image download probe.
 *
 * <p>Only a small prefix is fetched. A result is accepted when the current URL
 * responds successfully and the payload has a known passive image signature.
 * HTML error pages, expired hotlinks and active SVG content are rejected.</p>
 */
@Component
public class SecureImageDownloadVerifier implements ImageDownloadVerifier {
    private final UrlSafetyGuard safetyGuard;
    private final WebCapabilityProperties properties;
    private final HttpClient client;
    private final ExecutorService executor;

    public SecureImageDownloadVerifier(UrlSafetyGuard safetyGuard, WebCapabilityProperties properties) {
        this.safetyGuard = safetyGuard;
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getImageSearch().getDownloadValidationTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        int concurrency = Math.max(1, Math.min(16, properties.getImageSearch().getDownloadValidationConcurrency()));
        int queueCapacity = Math.max(concurrency, Math.min(1024,
                properties.getImageSearch().getDownloadValidationQueueCapacity()));
        this.executor = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "openreach-image-probe");
                    thread.setDaemon(true);
                    return thread;
                },
                // Backpressure instead of an unbounded work queue under public traffic.
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Override
    public boolean isDownloadable(String imageUrl) {
        try {
            URI current = safetyGuard.validate(imageUrl);
            int redirects = 0;
            while (true) {
                HttpRequest request = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofMillis(properties.getImageSearch().getDownloadValidationTimeoutMs()))
                        .header("User-Agent", properties.getImageSearch().getUserAgent())
                        .header("Accept", "image/avif,image/webp,image/apng,image/png,image/jpeg,image/gif,image/bmp,image/tiff,*/*;q=0.1")
                        .header("Accept-Encoding", "identity")
                        .header("Range", "bytes=0-65535")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    response.body().close();
                    if (++redirects > properties.getImageSearch().getDownloadValidationMaxRedirects()) return false;
                    Optional<String> location = response.headers().firstValue("location");
                    if (location.isEmpty()) return false;
                    current = safetyGuard.validate(current.resolve(location.get()).toString());
                    continue;
                }
                if (status < 200 || status >= 300) {
                    response.body().close();
                    return false;
                }

                String contentType = response.headers().firstValue("content-type")
                        .orElse("application/octet-stream").toLowerCase(Locale.ROOT);
                if (contentType.contains("svg") || contentType.startsWith("text/")
                        || contentType.contains("html") || contentType.contains("xml")) {
                    response.body().close();
                    return false;
                }
                int maxBytes = Math.max(64, properties.getImageSearch().getDownloadValidationMaxBytes());
                byte[] prefix = readPrefix(response.body(), maxBytes);
                return hasPassiveImageSignature(prefix);
            }
        } catch (Exception ignored) {
            // A failed probe means the URL is not safe/reliably downloadable now.
            return false;
        }
    }

    @Override
    public List<ImageSearchItem> filterDownloadable(List<ImageSearchItem> candidates, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) return List.of();
        List<CompletableFuture<Boolean>> futures = new ArrayList<>(candidates.size());
        for (ImageSearchItem item : candidates) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> item != null && item.imageUrl() != null && !item.imageUrl().isBlank()
                            && isDownloadable(item.imageUrl()), executor));
        }
        List<ImageSearchItem> accepted = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            boolean valid;
            try {
                valid = futures.get(i).join();
            } catch (RuntimeException ex) {
                valid = false;
            }
            if (valid) {
                accepted.add(candidates.get(i));
                if (accepted.size() >= limit) break;
            }
        }
        // Best-effort cancellation for tasks beyond the requested result count.
        if (accepted.size() >= limit) futures.forEach(f -> f.cancel(true));
        return accepted;
    }

    byte[] readPrefix(InputStream input, int maxBytes) throws IOException {
        try (input; ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 8192))) {
            byte[] buffer = new byte[4096];
            int remaining = maxBytes;
            while (remaining > 0) {
                int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
                // File signatures are at the start; 32 bytes is already enough for all supported types.
                if (out.size() >= 64) break;
            }
            return out.toByteArray();
        }
    }

    static boolean hasPassiveImageSignature(byte[] b) {
        if (b == null) return false;
        if (b.length >= 3 && u(b[0]) == 0xFF && u(b[1]) == 0xD8 && u(b[2]) == 0xFF) return true; // JPEG
        if (b.length >= 8 && u(b[0]) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && u(b[4]) == 0x0D && u(b[5]) == 0x0A && u(b[6]) == 0x1A && u(b[7]) == 0x0A) return true;
        if (b.length >= 6 && ascii(b, 0, 6).matches("GIF8[79]a")) return true;
        if (b.length >= 12 && "RIFF".equals(ascii(b, 0, 4)) && "WEBP".equals(ascii(b, 8, 4))) return true;
        if (b.length >= 2 && b[0] == 'B' && b[1] == 'M') return true; // BMP
        if (b.length >= 4 && ((b[0] == 'I' && b[1] == 'I' && u(b[2]) == 42 && b[3] == 0)
                || (b[0] == 'M' && b[1] == 'M' && b[2] == 0 && u(b[3]) == 42))) return true; // TIFF
        if (b.length >= 4 && b[0] == 0 && b[1] == 0 && b[2] == 1 && b[3] == 0) return true; // ICO
        if (b.length >= 12 && "ftyp".equals(ascii(b, 4, 4))) {
            String brand = ascii(b, 8, 4).toLowerCase(Locale.ROOT);
            return brand.equals("avif") || brand.equals("avis") || brand.equals("heic")
                    || brand.equals("heix") || brand.equals("mif1") || brand.equals("msf1");
        }
        return false;
    }

    private static int u(byte value) { return Byte.toUnsignedInt(value); }

    private static String ascii(byte[] bytes, int offset, int length) {
        if (bytes.length < offset + length) return "";
        return new String(bytes, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
