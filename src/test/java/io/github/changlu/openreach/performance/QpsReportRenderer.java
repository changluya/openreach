package io.github.changlu.openreach.performance;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders the human-readable Markdown report for the opt-in QPS benchmark.
 *
 * <p>The report intentionally keeps the CSV schema stable for machine consumption,
 * while making the Markdown report Chinese-first and decision-oriented.</p>
 */
final class QpsReportRenderer {

    private QpsReportRenderer() {
    }

    static String renderMarkdown(List<Result> results, Config config) {
        Result peak = results.stream().max(Comparator.comparingDouble(Result::qps)).orElse(null);
        long totalRequests = results.stream().mapToLong(Result::requests).sum();
        long totalSuccesses = results.stream().mapToLong(Result::successes).sum();
        long totalFailures = results.stream().mapToLong(Result::failures).sum();
        double successRate = percentage(totalSuccesses, totalRequests);
        boolean successPassed = totalFailures == 0;
        boolean peakPassed = peak != null && (config.minPeakQps() <= 0.0 || peak.qps() >= config.minPeakQps());
        boolean passed = successPassed && peakPassed;

        StringBuilder out = new StringBuilder();
        out.append("# OpenReach HTTP QPS 性能压测报告\n\n")
                .append("> **压测结论：").append(passed ? "✅ 通过" : "❌ 未通过").append("**。")
                .append("本报告用于评估 OpenReach 应用自身 HTTP/Search 调用链性能，不代表真实公网搜索渠道容量。\n\n")
                .append("## 一、核心结论\n\n");

        out.append("- **请求成功率**：").append(formatPercent(successRate))
                .append("（共 ").append(totalRequests).append(" 个正式压测请求，成功 ")
                .append(totalSuccesses).append("，失败 ").append(totalFailures).append("）。\n");
        if (peak != null) {
            out.append("- **峰值吞吐**：**").append(format(peak.qps())).append(" QPS**，出现在并发 **")
                    .append(peak.concurrency()).append("**；该档 P95/P99 分别为 **")
                    .append(format(peak.p95Ms())).append(" ms / ").append(format(peak.p99Ms())).append(" ms**。\n");
        }
        out.append("- **吞吐趋势**：").append(trendSummary(results)).append("\n")
                .append("- **稳定性判断**：").append(stabilitySummary(results)).append("\n");
        if (config.minPeakQps() > 0.0 && peak != null) {
            out.append("- **QPS 门槛**：要求峰值 ≥ ").append(format(config.minPeakQps()))
                    .append(" QPS，实测 ").append(format(peak.qps())).append(" QPS，")
                    .append(peakPassed ? "**达标**" : "**未达标**").append("。\n");
        } else {
            out.append("- **QPS 门槛**：未设置硬性峰值门槛（`MIN_PEAK_QPS=0`），本次以成功率、吞吐趋势和延迟表现为主。\n");
        }
        out.append("- **阅读建议**：容量判断应同时观察 **QPS + P95 + P99 + 成功率**，不要只看最高 QPS。\n\n")
                .append("## 二、测试范围与环境\n\n")
                .append("| 项目 | 本次配置 |\n")
                .append("|---|---|\n")
                .append("| 生成时间 | ").append(config.generatedAt()).append("（UTC） |\n")
                .append("| 测试接口 | `POST /api/web/search` |\n")
                .append("| Provider | 内存 `benchmark` Provider，不访问公网三方渠道 |\n")
                .append("| 并发档位 | ").append(concurrencyLevels(results)).append(" |\n")
                .append("| 每档请求数 | ").append(config.requestsPerLevel()).append(" |\n")
                .append("| 预热请求数 | ").append(config.warmupRequests()).append(" |\n")
                .append("| 正式压测请求总数 | ").append(totalRequests).append(" |\n")
                .append("| 可用处理器 | ").append(config.availableProcessors()).append(" |\n")
                .append("| Java 版本 | ").append(config.javaVersion()).append(" |\n")
                .append("| 模拟 Provider 延迟 | ").append(config.providerDelayMs()).append(" ms |\n")
                .append("| API 日志级别 | ").append(config.apiLogLevel()).append(" |\n")
                .append("| Upstream 日志级别 | ").append(config.upstreamLogLevel()).append(" |\n")
                .append("| 峰值 QPS 验收线 | ")
                .append(config.minPeakQps() > 0.0 ? format(config.minPeakQps()) + " QPS" : "未设置，仅报告")
                .append(" |\n\n")
                .append("### 本次压测实际覆盖什么\n\n")
                .append("本测试启动真实 Spring Boot HTTP 服务，并通过 HTTP 调用 `/api/web/search`，覆盖 Controller、Filter、")
                .append("JSON 序列化、Trace/日志、Search 编排等应用调用链。Provider 使用确定性的内存实现，因此**不会把公网延迟、三方限流、反爬或出口网络抖动混入应用自身 QPS**。\n\n")
                .append("## 三、各并发档详细结果\n\n")
                .append("| 并发 | 请求数 | 成功 | 失败 | 成功率 | 总耗时(ms) | QPS | 平均(ms) | P50(ms) | P95(ms) | P99(ms) | 最大(ms) | HTTP状态 |\n")
                .append("|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for (Result result : results) {
            out.append('|').append(result.concurrency())
                    .append('|').append(result.requests())
                    .append('|').append(result.successes())
                    .append('|').append(result.failures())
                    .append('|').append(formatPercent(percentage(result.successes(), result.requests())))
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

        out.append("\n## 四、吞吐与延迟趋势分析\n\n");
        if (results.size() < 2) {
            out.append("当前只配置了一个并发档位，无法判断吞吐扩展趋势。建议至少测试 3 个递增并发档位。\n");
        } else {
            out.append("| 并发变化 | QPS变化 | P95变化 | 自动判断 |\n")
                    .append("|---|---:|---:|---|\n");
            for (int i = 1; i < results.size(); i++) {
                Result previous = results.get(i - 1);
                Result current = results.get(i);
                double qpsDelta = relativeChange(current.qps(), previous.qps());
                double p95Delta = relativeChange(current.p95Ms(), previous.p95Ms());
                out.append('|').append(previous.concurrency()).append(" → ").append(current.concurrency())
                        .append('|').append(formatSignedPercent(qpsDelta))
                        .append('|').append(formatSignedPercent(p95Delta))
                        .append('|').append(trendLabel(qpsDelta, p95Delta)).append("|\n");
            }
        }

        out.append("\n### 拐点/容量信号\n\n")
                .append(inflectionSummary(results)).append("\n\n")
                .append("## 五、验收结果\n\n")
                .append("| 验收项 | 结果 | 说明 |\n")
                .append("|---|---|---|\n")
                .append("| 请求零失败 | ").append(successPassed ? "✅ 通过" : "❌ 未通过")
                .append(" | 失败请求：").append(totalFailures).append(" |\n")
                .append("| 峰值 QPS 门槛 | ").append(peakPassed ? "✅ 通过" : "❌ 未通过").append(" | ");
        if (config.minPeakQps() > 0.0 && peak != null) {
            out.append("要求 ≥ ").append(format(config.minPeakQps())).append("，实测 ").append(format(peak.qps()));
        } else {
            out.append("未设置硬门槛（MIN_PEAK_QPS=0）");
        }
        out.append(" |\n")
                .append("| 综合结论 | **").append(passed ? "✅ 通过" : "❌ 未通过").append("** | ")
                .append(passed ? "本次应用链路 QPS 基准无阻断项" : "请优先检查失败请求或峰值 QPS 回归")
                .append(" |\n\n")
                .append("## 六、指标说明\n\n")
                .append("| 指标 | 含义 |\n")
                .append("|---|---|\n")
                .append("| 并发 | 同时工作的请求 Worker 数 |\n")
                .append("| QPS | 每秒完成的请求数，`请求总数 / 总耗时秒数` |\n")
                .append("| 平均延迟 | 所有有效请求的平均耗时 |\n")
                .append("| P50 | 50% 请求的耗时不超过该值 |\n")
                .append("| P95 | 95% 请求的耗时不超过该值，容量评估重点关注 |\n")
                .append("| P99 | 99% 请求的耗时不超过该值，用于观察尾延迟 |\n")
                .append("| 最大延迟 | 本档位最慢请求耗时 |\n")
                .append("| 成功率 | HTTP 200 请求数 / 请求总数 |\n\n")
                .append("## 七、边界说明与下一步\n\n")
                .append("1. **本报告是 OpenReach 应用链路基准，不是 Bing/Baidu/Brave/DuckDuckGo 等真实 Provider 的 QPS 承诺。**\n")
                .append("2. 真实公网 QPS 还会受到三方响应时间、429 限流、反爬、出口 IP 信誉、DNS/TLS 和网络质量影响。\n")
                .append("3. 如需评估真实上游能力，请在已启动服务上使用 `./bin/quick/qps-test.sh`；免费 Provider 建议从低并发、小请求量开始。\n")
                .append("4. 若需要做版本性能回归，可在固定机器上设置 `MIN_PEAK_QPS`，并同时保留 P95/P99 对比，避免只追求吞吐导致尾延迟恶化。\n");
        return out.toString();
    }

    static String renderCsv(List<Result> results) {
        // CSV header stays English intentionally to preserve existing machine/CI parsing compatibility.
        StringBuilder csv = new StringBuilder("concurrency,requests,successes,failures,duration_ms,qps,avg_ms,p50_ms,p95_ms,p99_ms,max_ms,statuses\n");
        for (Result result : results) {
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
        return csv.toString();
    }

    private static String concurrencyLevels(List<Result> results) {
        return results.stream().map(result -> Integer.toString(result.concurrency())).collect(Collectors.joining(", "));
    }

    private static String trendSummary(List<Result> results) {
        if (results.isEmpty()) return "无有效压测数据。";
        if (results.size() == 1) return "仅一个并发档位，暂不能判断扩展趋势。";
        Result first = results.get(0);
        Result peak = results.stream().max(Comparator.comparingDouble(Result::qps)).orElse(first);
        double increase = relativeChange(peak.qps(), first.qps());
        if (peak == results.get(results.size() - 1)) {
            return "QPS 随并发整体提升，最高并发档仍取得峰值；相对首档提升 " + formatSignedPercent(increase)
                    + "，当前测试范围内未看到峰值后的明显吞吐回落。";
        }
        return "峰值出现在并发 " + peak.concurrency() + "，高于该并发后吞吐未继续提升；相对首档峰值提升 "
                + formatSignedPercent(increase) + "，建议重点观察峰值附近并发。";
    }

    private static String stabilitySummary(List<Result> results) {
        long total = results.stream().mapToLong(Result::requests).sum();
        long failures = results.stream().mapToLong(Result::failures).sum();
        if (total == 0) return "无正式压测请求。";
        if (failures == 0) return "所有并发档均为 0 失败，应用链路在本次压测范围内保持稳定。";
        return "共出现 " + failures + " 个失败请求，成功率 " + formatPercent(percentage(total - failures, total))
                + "，需要结合 HTTP 状态和日志定位后再做容量结论。";
    }

    private static String inflectionSummary(List<Result> results) {
        if (results.size() < 2) return "- 暂无足够档位判断性能拐点。";
        Result peak = results.stream().max(Comparator.comparingDouble(Result::qps)).orElse(results.get(0));
        Result last = results.get(results.size() - 1);
        if (peak != last) {
            double drop = relativeChange(last.qps(), peak.qps());
            return "- **发现明显容量信号**：峰值在并发 **" + peak.concurrency() + "**，到最高并发 **"
                    + last.concurrency() + "** 时 QPS 相对峰值变化 **" + formatSignedPercent(drop)
                    + "**。建议把并发 " + peak.concurrency() + " 附近作为下一轮细化测试区间。";
        }
        for (int i = 1; i < results.size(); i++) {
            Result previous = results.get(i - 1);
            Result current = results.get(i);
            double qpsDelta = relativeChange(current.qps(), previous.qps());
            double p95Delta = relativeChange(current.p95Ms(), previous.p95Ms());
            if (qpsDelta < 10.0 || (p95Delta > 30.0 && qpsDelta < 20.0)) {
                return "- **出现收益趋缓信号**：并发 **" + previous.concurrency() + " → " + current.concurrency()
                        + "** 时 QPS 变化 **" + formatSignedPercent(qpsDelta) + "**，P95 变化 **"
                        + formatSignedPercent(p95Delta) + "**。这不是硬性瓶颈结论，但建议在该区间增加并发档位复测。";
            }
        }
        return "- **当前未发现明显拐点**：已测试范围内吞吐仍有较好扩展性。若要寻找容量上限，可逐步增加并发档位，同时观察 P95/P99 和失败率。";
    }

    private static String trendLabel(double qpsDelta, double p95Delta) {
        String throughput;
        if (qpsDelta < -5.0) throughput = "⚠️ 吞吐回落";
        else if (qpsDelta < 10.0) throughput = "⚠️ 收益趋缓";
        else throughput = "✅ 吞吐提升";

        if (p95Delta > 30.0) return throughput + "，P95 明显上升";
        if (p95Delta < -20.0) return throughput + "，P95 有所下降";
        return throughput + "，延迟变化可控";
    }

    private static double relativeChange(double current, double previous) {
        if (previous == 0.0) return current == 0.0 ? 0.0 : 100.0;
        return (current - previous) * 100.0 / previous;
    }

    private static double percentage(long numerator, long denominator) {
        if (denominator <= 0) return 0.0;
        return numerator * 100.0 / denominator;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String formatPercent(double value) {
        return format(value) + "%";
    }

    private static String formatSignedPercent(double value) {
        return String.format(Locale.ROOT, "%+.2f%%", value);
    }

    record Result(
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
    ) {
    }

    record Config(
            Instant generatedAt,
            int requestsPerLevel,
            int warmupRequests,
            int availableProcessors,
            String javaVersion,
            long providerDelayMs,
            String apiLogLevel,
            String upstreamLogLevel,
            double minPeakQps
    ) {
    }
}
