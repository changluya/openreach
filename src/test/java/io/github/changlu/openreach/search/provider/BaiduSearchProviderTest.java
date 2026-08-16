package io.github.changlu.openreach.search.provider;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import io.github.changlu.openreach.search.SearchTimeRange;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaiduSearchProviderTest {
    @Test
    void parsesOrganicResultAndKeepsBaiduRedirect() {
        String html = """
                <div id="content_left">
                  <div class="result c-container"><h3><a href="/link?url=abc123">Spring Boot 中文资料</a></h3>
                  <div class="c-abstract">Spring Boot 相关文档和教程。</div></div>
                </div>
                """;
        var provider = new BaiduSearchProvider(null, new WebCapabilityProperties());
        var items = provider.parseResults(Jsoup.parse(html), 5);
        assertEquals(1, items.size());
        assertEquals("Spring Boot 中文资料", items.get(0).title());
        assertTrue(items.get(0).url().startsWith("https://www.baidu.com/link?url="));
        assertEquals("baidu", items.get(0).source());
    }

    @Test
    void supportsAllRequestedTimeRangesThroughFreeWebSearch() {
        var provider = new BaiduSearchProvider(null, new WebCapabilityProperties());
        assertTrue(provider.supportsTimeRange(SearchTimeRange.DAY));
        assertTrue(provider.supportsTimeRange(SearchTimeRange.WEEK));
        assertTrue(provider.supportsTimeRange(SearchTimeRange.MONTH));
        assertTrue(provider.supportsTimeRange(SearchTimeRange.YEAR));
    }

    @Test
    void mapsDayWeekMonthYearToVerifiedBaiduEpochRanges() {
        var provider = new BaiduSearchProvider(null, new WebCapabilityProperties());
        long end = 1_800_000_000L;

        assertBaiduRange(provider, SearchTimeRange.DAY, end, 86_400L, 21);
        assertBaiduRange(provider, SearchTimeRange.WEEK, end, 7L * 86_400L, 22);
        assertBaiduRange(provider, SearchTimeRange.MONTH, end, 30L * 86_400L, 23);
        assertBaiduRange(provider, SearchTimeRange.YEAR, end, 365L * 86_400L, 24);
    }

    @Test
    void detectsBaiduCaptchaRedirectAsBotChallengePage() {
        var provider = new BaiduSearchProvider(null, new WebCapabilityProperties());
        var doc = Jsoup.parse("<html><head><title>百度安全验证</title></head><body>安全验证</body></html>",
                "https://wappass.baidu.com/static/captcha/tuxing_v2.html?backurl=x");
        assertTrue(provider.isCaptcha(doc));
    }

    @Test
    void anyDoesNotAddBaiduTimeFilter() {
        var provider = new BaiduSearchProvider(null, new WebCapabilityProperties());
        var params = queryParams(provider.buildUri("AI 融资", 10, SearchTimeRange.ANY, 1_800_000_000L));
        assertEquals("AI 融资", params.get("wd"));
        assertTrue(!params.containsKey("word"));
        assertTrue(!params.containsKey("gpc"));
        assertTrue(!params.containsKey("timefactor"));
        assertTrue(!params.containsKey("tfflag"));
    }

    private void assertBaiduRange(BaiduSearchProvider provider, SearchTimeRange range, long end,
                                  long expectedSeconds, int expectedFactor) {
        var uri = provider.buildUri("AI 融资 投资 最新", 10, range, end);
        Map<String, String> params = queryParams(uri);
        assertEquals("AI 融资 投资 最新", params.get("word"));
        assertEquals("1", params.get("tfflag"));
        assertEquals(Integer.toString(expectedFactor), params.get("timefactor"));
        assertEquals("stf=" + (end - expectedSeconds) + "," + end + "|stftype=1", params.get("gpc"));
    }

    private Map<String, String> queryParams(java.net.URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            params.put(parts[0], value);
        }
        return params;
    }
}
