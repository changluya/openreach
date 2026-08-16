package io.github.changlu.openreach.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "openreach.web")
public class WebCapabilityProperties {
    private final Routing routing = new Routing();
    private final Search search = new Search();
    private final ImageSearch imageSearch = new ImageSearch();
    private final Read read = new Read();
    private final Security security = new Security();

    public Routing getRouting() { return routing; }
    public Search getSearch() { return search; }
    public ImageSearch getImageSearch() { return imageSearch; }
    public Read getRead() { return read; }
    public Security getSecurity() { return security; }

    /**
     * Cross-capability routing options.  The external API keeps using the existing
     * region field; internally it is normalized to a small route enum so future
     * versions can add JP/EU/custom routes without leaking routing logic into every
     * provider.
     */
    public static class Routing {
        private String defaultRoute = "cn";

        public String getDefaultRoute() { return defaultRoute; }
        public void setDefaultRoute(String defaultRoute) { this.defaultRoute = defaultRoute; }
    }

    public static class Search {
        private String provider = "auto";

        /**
         * v1.0.1 compatibility knob.  If cn-provider-order is empty this list is
         * used for the CN route, so existing deployments do not need to rewrite
         * their configuration immediately.
         */
        private List<String> providerOrder = new ArrayList<>(List.of("bing", "baidu", "sogou", "so360", "duckduckgo"));
        private List<String> cnProviderOrder = new ArrayList<>();
        private List<String> globalProviderOrder = new ArrayList<>(List.of("brave", "duckduckgo", "bing"));
        // Restricted timeRange uses only providers with verified native upstream filtering.
        private List<String> cnTimeRangeProviderOrder = new ArrayList<>(List.of("baidu", "bing", "duckduckgo", "brave"));
        private List<String> globalTimeRangeProviderOrder = new ArrayList<>(List.of("bing", "brave", "duckduckgo", "baidu"));

        private int timeoutMs = 6000;
        private int maxResults = 20;
        private int maxResponseBytes = 2 * 1024 * 1024;
        private String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
        private String bingUrl = "https://cn.bing.com/search";
        private String bingGlobalUrl = "https://www.bing.com/search";
        private String braveUrl = "https://search.brave.com/search";
        private String baiduUrl = "https://www.baidu.com/s";
        private String sogouUrl = "https://www.sogou.com/web";
        private String so360Url = "https://www.so.com/s";
        private String duckduckgoUrl = "https://html.duckduckgo.com/html/";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public List<String> getProviderOrder() { return providerOrder; }
        public void setProviderOrder(List<String> providerOrder) { this.providerOrder = safeList(providerOrder); }
        public List<String> getCnProviderOrder() { return cnProviderOrder; }
        public void setCnProviderOrder(List<String> cnProviderOrder) { this.cnProviderOrder = safeList(cnProviderOrder); }
        public List<String> getGlobalProviderOrder() { return globalProviderOrder; }
        public void setGlobalProviderOrder(List<String> globalProviderOrder) { this.globalProviderOrder = safeList(globalProviderOrder); }
        public List<String> getCnTimeRangeProviderOrder() { return cnTimeRangeProviderOrder; }
        public void setCnTimeRangeProviderOrder(List<String> value) { this.cnTimeRangeProviderOrder = safeList(value); }
        public List<String> getGlobalTimeRangeProviderOrder() { return globalTimeRangeProviderOrder; }
        public void setGlobalTimeRangeProviderOrder(List<String> value) { this.globalTimeRangeProviderOrder = safeList(value); }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
        public int getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
        public String getBingUrl() { return bingUrl; }
        public void setBingUrl(String bingUrl) { this.bingUrl = bingUrl; }
        public String getBingGlobalUrl() { return bingGlobalUrl; }
        public void setBingGlobalUrl(String bingGlobalUrl) { this.bingGlobalUrl = bingGlobalUrl; }
        public String getBraveUrl() { return braveUrl; }
        public void setBraveUrl(String braveUrl) { this.braveUrl = braveUrl; }
        public String getBaiduUrl() { return baiduUrl; }
        public void setBaiduUrl(String baiduUrl) { this.baiduUrl = baiduUrl; }
        public String getSogouUrl() { return sogouUrl; }
        public void setSogouUrl(String sogouUrl) { this.sogouUrl = sogouUrl; }
        public String getSo360Url() { return so360Url; }
        public void setSo360Url(String so360Url) { this.so360Url = so360Url; }
        public String getDuckduckgoUrl() { return duckduckgoUrl; }
        public void setDuckduckgoUrl(String duckduckgoUrl) { this.duckduckgoUrl = duckduckgoUrl; }

        public List<String> effectiveCnProviderOrder() {
            return cnProviderOrder == null || cnProviderOrder.isEmpty() ? providerOrder : cnProviderOrder;
        }

        private static List<String> safeList(List<String> value) {
            return value == null ? new ArrayList<>() : new ArrayList<>(value);
        }
    }

    public static class ImageSearch {
        private String provider = "auto";
        private List<String> providerOrder = new ArrayList<>(List.of("bing", "baidu", "sogou", "openverse"));
        private List<String> cnProviderOrder = new ArrayList<>();
        private List<String> globalProviderOrder = new ArrayList<>(List.of("bing", "openverse", "wikimedia"));

        private int timeoutMs = 8000;
        private int maxResults = 30;
        private int maxResponseBytes = 4 * 1024 * 1024;
        private String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
        private String bingUrl = "https://cn.bing.com/images/async";
        private String bingGlobalUrl = "https://www.bing.com/images/async";
        private String baiduBaseUrl = "https://image.baidu.com/";
        private String baiduUrl = "https://image.baidu.com/search/acjson";
        private String sogouUrl = "https://pic.sogou.com/pics";
        private String openverseUrl = "https://api.openverse.org/v1/images/";
        private String wikimediaUrl = "https://commons.wikimedia.org/w/api.php";
        private String wikimediaUserAgent = "OpenReach/1.0.2 (+https://github.com/changluya/openreach)";
        // Image results are only returned after a bounded secure download probe.
        private int downloadCandidateMultiplier = 3;
        private int downloadMaxCandidates = 60;
        private int downloadValidationTimeoutMs = 4000;
        private int downloadValidationMaxRedirects = 3;
        private int downloadValidationMaxBytes = 65536;
        private int downloadValidationConcurrency = 6;
        private int downloadValidationQueueCapacity = 48;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public List<String> getProviderOrder() { return providerOrder; }
        public void setProviderOrder(List<String> providerOrder) { this.providerOrder = safeList(providerOrder); }
        public List<String> getCnProviderOrder() { return cnProviderOrder; }
        public void setCnProviderOrder(List<String> cnProviderOrder) { this.cnProviderOrder = safeList(cnProviderOrder); }
        public List<String> getGlobalProviderOrder() { return globalProviderOrder; }
        public void setGlobalProviderOrder(List<String> globalProviderOrder) { this.globalProviderOrder = safeList(globalProviderOrder); }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
        public int getMaxResponseBytes() { return maxResponseBytes; }
        public void setMaxResponseBytes(int maxResponseBytes) { this.maxResponseBytes = maxResponseBytes; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
        public String getBingUrl() { return bingUrl; }
        public void setBingUrl(String bingUrl) { this.bingUrl = bingUrl; }
        public String getBingGlobalUrl() { return bingGlobalUrl; }
        public void setBingGlobalUrl(String bingGlobalUrl) { this.bingGlobalUrl = bingGlobalUrl; }
        public String getBaiduBaseUrl() { return baiduBaseUrl; }
        public void setBaiduBaseUrl(String baiduBaseUrl) { this.baiduBaseUrl = baiduBaseUrl; }
        public String getBaiduUrl() { return baiduUrl; }
        public void setBaiduUrl(String baiduUrl) { this.baiduUrl = baiduUrl; }
        public String getSogouUrl() { return sogouUrl; }
        public void setSogouUrl(String sogouUrl) { this.sogouUrl = sogouUrl; }
        public String getOpenverseUrl() { return openverseUrl; }
        public void setOpenverseUrl(String openverseUrl) { this.openverseUrl = openverseUrl; }
        public String getWikimediaUrl() { return wikimediaUrl; }
        public void setWikimediaUrl(String wikimediaUrl) { this.wikimediaUrl = wikimediaUrl; }
        public String getWikimediaUserAgent() { return wikimediaUserAgent; }
        public void setWikimediaUserAgent(String wikimediaUserAgent) { this.wikimediaUserAgent = wikimediaUserAgent; }
        public int getDownloadCandidateMultiplier() { return downloadCandidateMultiplier; }
        public void setDownloadCandidateMultiplier(int downloadCandidateMultiplier) { this.downloadCandidateMultiplier = downloadCandidateMultiplier; }
        public int getDownloadMaxCandidates() { return downloadMaxCandidates; }
        public void setDownloadMaxCandidates(int downloadMaxCandidates) { this.downloadMaxCandidates = downloadMaxCandidates; }
        public int getDownloadValidationTimeoutMs() { return downloadValidationTimeoutMs; }
        public void setDownloadValidationTimeoutMs(int downloadValidationTimeoutMs) { this.downloadValidationTimeoutMs = downloadValidationTimeoutMs; }
        public int getDownloadValidationMaxRedirects() { return downloadValidationMaxRedirects; }
        public void setDownloadValidationMaxRedirects(int downloadValidationMaxRedirects) { this.downloadValidationMaxRedirects = downloadValidationMaxRedirects; }
        public int getDownloadValidationMaxBytes() { return downloadValidationMaxBytes; }
        public void setDownloadValidationMaxBytes(int downloadValidationMaxBytes) { this.downloadValidationMaxBytes = downloadValidationMaxBytes; }
        public int getDownloadValidationConcurrency() { return downloadValidationConcurrency; }
        public void setDownloadValidationConcurrency(int downloadValidationConcurrency) { this.downloadValidationConcurrency = downloadValidationConcurrency; }
        public int getDownloadValidationQueueCapacity() { return downloadValidationQueueCapacity; }
        public void setDownloadValidationQueueCapacity(int downloadValidationQueueCapacity) { this.downloadValidationQueueCapacity = downloadValidationQueueCapacity; }

        public List<String> effectiveCnProviderOrder() {
            return cnProviderOrder == null || cnProviderOrder.isEmpty() ? providerOrder : cnProviderOrder;
        }

        private static List<String> safeList(List<String> value) {
            return value == null ? new ArrayList<>() : new ArrayList<>(value);
        }
    }

    public static class Read {
        private int timeoutMs = 10000;
        private int maxBytes = 5 * 1024 * 1024;
        private int maxChars = 50000;
        private int maxRedirects = 5;
        private List<Integer> allowedPorts = new ArrayList<>(List.of(80, 443));
        private String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
        private String acceptLanguage = "zh-CN,zh;q=0.9,en;q=0.6";

        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxBytes() { return maxBytes; }
        public void setMaxBytes(int maxBytes) { this.maxBytes = maxBytes; }
        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
        public int getMaxRedirects() { return maxRedirects; }
        public void setMaxRedirects(int maxRedirects) { this.maxRedirects = maxRedirects; }
        public List<Integer> getAllowedPorts() { return allowedPorts; }
        public void setAllowedPorts(List<Integer> allowedPorts) { this.allowedPorts = allowedPorts == null ? new ArrayList<>() : new ArrayList<>(allowedPorts); }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
        public String getAcceptLanguage() { return acceptLanguage; }
        public void setAcceptLanguage(String acceptLanguage) { this.acceptLanguage = acceptLanguage; }
    }
    public static class Security {
        // All public JSON APIs are tiny control payloads; reject oversized/chunked abuse early.
        private int maxApiBodyBytes = 64 * 1024;

        public int getMaxApiBodyBytes() { return maxApiBodyBytes; }
        public void setMaxApiBodyBytes(int maxApiBodyBytes) { this.maxApiBodyBytes = maxApiBodyBytes; }
    }

}
