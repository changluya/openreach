package io.github.changlu.openreach.performance;

import io.github.changlu.openreach.search.SearchProvider;
import io.github.changlu.openreach.search.dto.SearchItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in HTTP concurrency benchmark for OpenReach.
 *
 * <p>This test starts the real Spring Boot HTTP stack on a random port and calls
 * {@code POST /api/web/search} concurrently. The search provider is an in-memory
 * deterministic test provider so the result reflects OpenReach's controller,
 * filters, JSON serialization, trace/logging and search orchestration rather
 * than public search-engine rate limits or network jitter.</p>
 *
 * <p>It is intentionally disabled during normal {@code mvn test}. Run it with:
 * {@code ./bin/quick/qps-unit-test.sh}</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(OpenReachApiQpsBenchmarkTest.QpsBenchmarkConfiguration.class)
@EnabledIfSystemProperty(named = "openreach.qps.enabled", matches = "true")
class OpenReachApiQpsBenchmarkTest {

    private static final String PAYLOAD = """
            {"query":"OpenReach QPS benchmark","limit":1,"region":"CN","provider":"benchmark"}
            """.trim();

    @LocalServerPort
    private int port;

    @Test
    void benchmarkSearchApiAcrossConcurrencyLevels() throws Exception {
        int requestsPerLevel = intProperty("openreach.qps.requestsPerLevel", 500, 1, 100_000);
        int warmupRequests = intProperty("openreach.qps.warmupRequests", 50, 0, 10_000);
        List<Integer> concurrencyLevels = concurrencyLevelsProperty();
        assertTrue(!concurrencyLevels.isEmpty(), "At least one concurrency level is required");
        double minPeakQps = doubleProperty("openreach.qps.minPeakQps", 0.0, 0.0, 1_000_000.0);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        URI endpoint = URI.create("http://127.0.0.1:" + port + "/api/web/search");

        warmup(client, endpoint, warmupRequests);

        List<BenchmarkResult> results = new ArrayList<>();
        for (int concurrency : concurrencyLevels) {
            BenchmarkResult result = runBenchmark(client, endpoint, requestsPerLevel, concurrency);
            results.add(result);
            assertEquals(requestsPerLevel, result.successes(),
                    "Every benchmark request must succeed; inspect logs/trace IDs when failures occur");
            assertEquals(0, result.failures(), "Benchmark request failures are not allowed");
            assertTrue(result.qps() > 0.0, "Measured QPS must be positive");
        }

        BenchmarkResult peak = results.stream().max(java.util.Comparator.comparingDouble(BenchmarkResult::qps)).orElseThrow();
        if (minPeakQps > 0.0) {
            assertTrue(peak.qps() >= minPeakQps,
                    "Peak QPS regression: expected >= " + minPeakQps + ", actual=" + format(peak.qps()));
        }

        writeReport(results, requestsPerLevel, warmupRequests);
        printReport(results, requestsPerLevel, warmupRequests);
    }

    private BenchmarkResult runBenchmark(HttpClient client, URI endpoint, int totalRequests, int concurrency)
            throws InterruptedException {
        int workers = Math.min(concurrency, totalRequests);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        long[] latenciesNanos = new long[totalRequests];
        Map<Integer, AtomicInteger> statuses = new java.util.concurrent.ConcurrentHashMap<>();

        for (int i = 0; i < workers; i++) {
            final int workerIndex = i;
            executor.execute(() -> {
                ready.countDown();
                try {
                    start.await();
                    for (int requestIndex = workerIndex; requestIndex < totalRequests; requestIndex += workers) {
                        HttpRequest request = HttpRequest.newBuilder(endpoint)
                                .timeout(Duration.ofSeconds(10))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(PAYLOAD, StandardCharsets.UTF_8))
                                .build();
                        long started = System.nanoTime();
                        try {
                            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                            long latency = System.nanoTime() - started;
                            latenciesNanos[requestIndex] = latency;
                            statuses.computeIfAbsent(response.statusCode(), ignored -> new AtomicInteger()).incrementAndGet();
                            if (response.statusCode() == 200) successes.incrementAndGet();
                            else failures.incrementAndGet();
                        } catch (IOException | InterruptedException ex) {
                            failures.incrementAndGet();
                            if (ex instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(10, TimeUnit.SECONDS), "Benchmark workers did not become ready in time");
        long benchmarkStarted = System.nanoTime();
        start.countDown();
        assertTrue(done.await(5, TimeUnit.MINUTES), "Benchmark did not finish within five minutes");
        long elapsedNanos = System.nanoTime() - benchmarkStarted;
        executor.shutdownNow();

        List<Long> sortedLatencies = Arrays.stream(latenciesNanos)
                .filter(value -> value > 0L)
                .boxed()
                .sorted()
                .toList();
        double durationSeconds = elapsedNanos / 1_000_000_000.0;
        double qps = totalRequests / durationSeconds;
        double averageMs = sortedLatencies.stream().mapToLong(Long::longValue).average().orElse(0.0) / 1_000_000.0;

        Map<Integer, Integer> immutableStatuses = new LinkedHashMap<>();
        statuses.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> immutableStatuses.put(entry.getKey(), entry.getValue().get()));

        return new BenchmarkResult(
                concurrency,
                totalRequests,
                successes.get(),
                failures.get(),
                elapsedNanos / 1_000_000.0,
                qps,
                averageMs,
                percentileMs(sortedLatencies, 0.50),
                percentileMs(sortedLatencies, 0.95),
                percentileMs(sortedLatencies, 0.99),
                percentileMs(sortedLatencies, 1.00),
                immutableStatuses
        );
    }

    private void warmup(HttpClient client, URI endpoint, int warmupRequests) throws IOException, InterruptedException {
        for (int i = 0; i < warmupRequests; i++) {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(PAYLOAD, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            assertEquals(200, response.statusCode(), "Warm-up request failed");
        }
    }

    private void printReport(List<BenchmarkResult> results, int requestsPerLevel, int warmupRequests) {
        String report = renderReport(results, requestsPerLevel, warmupRequests);
        System.out.println(report);
    }

    private void writeReport(List<BenchmarkResult> results, int requestsPerLevel, int warmupRequests) throws IOException {
        Path reportDir = Path.of("target", "qps");
        Files.createDirectories(reportDir);
        Files.writeString(reportDir.resolve("openreach-qps-report.md"),
                renderReport(results, requestsPerLevel, warmupRequests),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        StringBuilder csv = new StringBuilder("concurrency,requests,successes,failures,duration_ms,qps,avg_ms,p50_ms,p95_ms,p99_ms,max_ms,statuses\n");
        for (BenchmarkResult result : results) {
            csv.append(result.concurrency()).append(',')
                    .append(result.requests()).append(',')
                    .append(result.successes()).append(',')
                    .append(result.failures()).append(',')
                    .append(format(result.durationMs())).append(',')
                    .append(format(result.qps())).append(',')
                    .append(format(result.averageMs())).append(',')
                    .append(format(result.p50Ms())).append(',')
                    .append(format(result.p95Ms())).append(',')
                    .append(format(result.p99Ms())).append(',')
                    .append(format(result.maxMs())).append(',')
                    .append('"').append(result.statuses()).append('"').append('\n');
        }
        Files.writeString(reportDir.resolve("openreach-qps-report.csv"), csv.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private String renderReport(List<BenchmarkResult> results, int requestsPerLevel, int warmupRequests) {
        BenchmarkResult peak = results.stream().max(java.util.Comparator.comparingDouble(BenchmarkResult::qps)).orElse(null);
        StringBuilder out = new StringBuilder();
        out.append("# OpenReach HTTP QPS Benchmark\n\n")
                .append("- Generated: ").append(Instant.now()).append("\n")
                .append("- Endpoint: `POST /api/web/search`\n")
                .append("- Provider: in-memory `benchmark` provider (no public upstream network)\n")
                .append("- Requests per concurrency level: ").append(requestsPerLevel).append("\n")
                .append("- Warm-up requests: ").append(warmupRequests).append("\n")
                .append("- Available processors: ").append(Runtime.getRuntime().availableProcessors()).append("\n")
                .append("- Java: ").append(System.getProperty("java.version")).append("\n")
                .append("- Simulated provider delay: ").append(QpsBenchmarkConfiguration.providerDelayMs()).append(" ms\n")
                .append("- Logging: current application Logback configuration is kept enabled unless overridden with JVM/Spring logging properties.\n");
        if (peak != null) {
            out.append("- Peak measured QPS: **").append(format(peak.qps())).append("** @ concurrency **")
                    .append(peak.concurrency()).append("**\n");
        }
        out.append("\n")
                .append("| Concurrency | Requests | Success | Fail | Duration ms | QPS | Avg ms | P50 ms | P95 ms | P99 ms | Max ms | HTTP status |\n")
                .append("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for (BenchmarkResult result : results) {
            out.append('|').append(result.concurrency())
                    .append('|').append(result.requests())
                    .append('|').append(result.successes())
                    .append('|').append(result.failures())
                    .append('|').append(format(result.durationMs()))
                    .append('|').append(format(result.qps()))
                    .append('|').append(format(result.averageMs()))
                    .append('|').append(format(result.p50Ms()))
                    .append('|').append(format(result.p95Ms()))
                    .append('|').append(format(result.p99Ms()))
                    .append('|').append(format(result.maxMs()))
                    .append('|').append(result.statuses())
                    .append("|\n");
        }
        out.append("\n> This is an OpenReach application-path benchmark, not a claim about Bing/Baidu/Brave/DuckDuckGo throughput. "
                + "Real provider QPS is additionally constrained by upstream latency, rate limits, anti-bot controls, egress IP reputation and network quality.\n");
        return out.toString();
    }

    private static double percentileMs(List<Long> sortedNanos, double percentile) {
        if (sortedNanos.isEmpty()) return 0.0;
        int index = (int) Math.ceil(percentile * sortedNanos.size()) - 1;
        index = Math.max(0, Math.min(index, sortedNanos.size() - 1));
        return sortedNanos.get(index) / 1_000_000.0;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static int intProperty(String name, int defaultValue, int min, int max) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be an integer: " + raw, ex);
        }
    }

    private static double doubleProperty(String name, double defaultValue, double min, double max) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            double value = Double.parseDouble(raw.trim());
            if (!Double.isFinite(value) || value < min || value > max) {
                throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be numeric: " + raw, ex);
        }
    }

    private static List<Integer> concurrencyLevelsProperty() {
        String raw = System.getProperty("openreach.qps.concurrencyLevels", "1,4,8,16,32");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> {
                    try {
                        int concurrency = Integer.parseInt(value);
                        if (concurrency < 1 || concurrency > 512) {
                            throw new IllegalArgumentException("Concurrency must be between 1 and 512: " + concurrency);
                        }
                        return concurrency;
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("Invalid openreach.qps.concurrencyLevels value: " + value, ex);
                    }
                })
                .distinct()
                .toList();
    }

    record BenchmarkResult(
            int concurrency,
            int requests,
            int successes,
            int failures,
            double durationMs,
            double qps,
            double averageMs,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double maxMs,
            Map<Integer, Integer> statuses
    ) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class QpsBenchmarkConfiguration {
        @Bean
        SearchProvider benchmarkSearchProvider() {
            return new SearchProvider() {
                @Override
                public String name() {
                    return "benchmark";
                }

                @Override
                public List<SearchItem> search(String query, int limit, String region) {
                    long delayMs = providerDelayMs();
                    if (delayMs > 0) {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(delayMs));
                    }
                    return List.of(new SearchItem(
                            1,
                            "OpenReach benchmark",
                            "https://benchmark.openreach.local/result",
                            "deterministic in-memory benchmark result",
                            "benchmark"
                    ));
                }
            };
        }

        static long providerDelayMs() {
            String raw = System.getProperty("openreach.qps.providerDelayMs", "0");
            try {
                long value = Long.parseLong(raw.trim());
                if (value < 0 || value > 60_000) {
                    throw new IllegalArgumentException("openreach.qps.providerDelayMs must be between 0 and 60000");
                }
                return value;
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("openreach.qps.providerDelayMs must be an integer: " + raw, ex);
            }
        }
    }
}
