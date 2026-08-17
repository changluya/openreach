package io.github.changlu.openreach.performance;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QpsReportRendererTest {

    @Test
    void rendersChineseDecisionOrientedReportAndKeepsCsvHeaderCompatible() {
        List<QpsReportRenderer.Result> results = List.of(
                result(1, 500, 500, 0, 1000, 500, 2.0, 3.0, 4.0, 5.0, 8.0),
                result(4, 500, 500, 0, 500, 1000, 3.0, 4.0, 7.0, 9.0, 12.0),
                result(8, 500, 500, 0, 420, 1190, 5.0, 6.0, 12.0, 15.0, 20.0),
                result(16, 500, 500, 0, 430, 1160, 9.0, 10.0, 22.0, 30.0, 40.0)
        );
        QpsReportRenderer.Config config = new QpsReportRenderer.Config(
                Instant.parse("2026-08-17T03:00:00Z"),
                500,
                50,
                8,
                "17.0.15",
                0,
                "INFO",
                "INFO",
                1000
        );

        String markdown = QpsReportRenderer.renderMarkdown(results, config);
        assertTrue(markdown.contains("# OpenReach HTTP QPS 性能压测报告"));
        assertTrue(markdown.contains("## 一、核心结论"));
        assertTrue(markdown.contains("请求成功率"));
        assertTrue(markdown.contains("峰值吞吐"));
        assertTrue(markdown.contains("吞吐与延迟趋势分析"));
        assertTrue(markdown.contains("拐点/容量信号"));
        assertTrue(markdown.contains("验收结果"));
        assertTrue(markdown.contains("1190.00 QPS"));
        assertTrue(markdown.contains("并发 **8**"));
        assertTrue(markdown.contains("✅ 通过"));
        assertFalse(markdown.contains("OpenReach HTTP QPS Benchmark"));

        String csv = QpsReportRenderer.renderCsv(results);
        assertTrue(csv.startsWith("concurrency,requests,successes,failures,duration_ms,qps"));
    }

    @Test
    void marksReportFailedWhenRequestsFailOrPeakThresholdIsNotMet() {
        List<QpsReportRenderer.Result> results = List.of(
                result(4, 100, 99, 1, 1000, 100, 10.0, 9.0, 20.0, 25.0, 35.0)
        );
        QpsReportRenderer.Config config = new QpsReportRenderer.Config(
                Instant.parse("2026-08-17T03:00:00Z"), 100, 10, 4, "17", 0, "INFO", "INFO", 200
        );

        String markdown = QpsReportRenderer.renderMarkdown(results, config);
        assertTrue(markdown.contains("压测结论：❌ 未通过"));
        assertTrue(markdown.contains("失败 1"));
        assertTrue(markdown.contains("实测 100.00 QPS"));
    }

    private static QpsReportRenderer.Result result(int concurrency, int requests, int successes, int failures,
                                                   double durationMs, double qps, double avgMs, double p50Ms,
                                                   double p95Ms, double p99Ms, double maxMs) {
        return new QpsReportRenderer.Result(
                concurrency, requests, successes, failures, durationMs, qps, avgMs, p50Ms, p95Ms, p99Ms, maxMs,
                Map.of(200, successes)
        );
    }
}
