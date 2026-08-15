package io.github.changlu.openreach.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "openreach.web")
public class WebCapabilityProperties {
    private final Search search = new Search();
    private final ImageSearch imageSearch = new ImageSearch();
    private final Read read = new Read();

    public Search getSearch() { return search; }
    public ImageSearch getImageSearch() { return imageSearch; }
    public Read getRead() { return read; }

    public static class Search {
        private String provider = "auto";
        private List<String> providerOrder = new ArrayList<>(List.of("bing", "baidu", "sogou", "so360", "duckduckgo"));
        private int timeoutMs = 6000;
        private int maxResults = 20;
        private String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
        private String bingUrl = "https://cn.bing.com/search";
        private String baiduUrl = "https://www.baidu.com/s";
        private String sogouUrl = "https://www.sogou.com/web";
        private String so360Url = "https://www.so.com/s";
        private String duckduckgoUrl = "https://html.duckduckgo.com/html/";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public List<String> getProviderOrder() { return providerOrder; }
        public void setProviderOrder(List<String> providerOrder) { this.providerOrder = providerOrder; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
        public String getBingUrl() { return bingUrl; }
        public void setBingUrl(String bingUrl) { this.bingUrl = bingUrl; }
        public String getBaiduUrl() { return baiduUrl; }
        public void setBaiduUrl(String baiduUrl) { this.baiduUrl = baiduUrl; }
        public String getSogouUrl() { return sogouUrl; }
        public void setSogouUrl(String sogouUrl) { this.sogouUrl = sogouUrl; }
        public String getSo360Url() { return so360Url; }
        public void setSo360Url(String so360Url) { this.so360Url = so360Url; }
        public String getDuckduckgoUrl() { return duckduckgoUrl; }
        public void setDuckduckgoUrl(String duckduckgoUrl) { this.duckduckgoUrl = duckduckgoUrl; }
    }

    public static class ImageSearch {
        private String provider = "auto";
        private List<String> providerOrder = new ArrayList<>(List.of("bing", "baidu", "sogou", "openverse"));
        private int timeoutMs = 8000;
        private int maxResults = 30;
        private String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
        private String bingUrl = "https://cn.bing.com/images/async";
        private String baiduBaseUrl = "https://image.baidu.com/";
        private String baiduUrl = "https://image.baidu.com/search/acjson";
        private String sogouUrl = "https://pic.sogou.com/pics";
        private String openverseUrl = "https://api.openverse.org/v1/images/";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public List<String> getProviderOrder() { return providerOrder; }
        public void setProviderOrder(List<String> providerOrder) { this.providerOrder = providerOrder; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxResults() { return maxResults; }
        public void setMaxResults(int maxResults) { this.maxResults = maxResults; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
        public String getBingUrl() { return bingUrl; }
        public void setBingUrl(String bingUrl) { this.bingUrl = bingUrl; }
        public String getBaiduBaseUrl() { return baiduBaseUrl; }
        public void setBaiduBaseUrl(String baiduBaseUrl) { this.baiduBaseUrl = baiduBaseUrl; }
        public String getBaiduUrl() { return baiduUrl; }
        public void setBaiduUrl(String baiduUrl) { this.baiduUrl = baiduUrl; }
        public String getSogouUrl() { return sogouUrl; }
        public void setSogouUrl(String sogouUrl) { this.sogouUrl = sogouUrl; }
        public String getOpenverseUrl() { return openverseUrl; }
        public void setOpenverseUrl(String openverseUrl) { this.openverseUrl = openverseUrl; }
    }

    public static class Read {
        private int timeoutMs = 10000;
        private int maxBytes = 5 * 1024 * 1024;
        private int maxChars = 50000;
        private int maxRedirects = 5;
        private String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getMaxBytes() { return maxBytes; }
        public void setMaxBytes(int maxBytes) { this.maxBytes = maxBytes; }
        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int maxChars) { this.maxChars = maxChars; }
        public int getMaxRedirects() { return maxRedirects; }
        public void setMaxRedirects(int maxRedirects) { this.maxRedirects = maxRedirects; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    }
}
