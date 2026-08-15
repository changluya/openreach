package io.github.changlu.openreach.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionLocaleSupportTest {
    @Test
    void cnUsesChineseLocale() {
        assertEquals("CN", RegionLocaleSupport.countryCode("CN", SearchRoute.CN));
        assertEquals("zh-CN", RegionLocaleSupport.localeTag("CN", SearchRoute.CN));
        assertEquals("cn-zh", RegionLocaleSupport.duckDuckGoRegion("CN", SearchRoute.CN));
    }

    @Test
    void countryCodeMapsToUsefulGlobalLocale() {
        assertEquals("US", RegionLocaleSupport.countryCode("US", SearchRoute.GLOBAL));
        assertEquals("en-US", RegionLocaleSupport.localeTag("US", SearchRoute.GLOBAL));
        assertEquals("JP", RegionLocaleSupport.countryCode("ja-JP", SearchRoute.GLOBAL));
        assertEquals("ja-JP", RegionLocaleSupport.localeTag("ja-JP", SearchRoute.GLOBAL));
    }

    @Test
    void worldwideRegionUsesNeutralDuckDuckGoRegion() {
        assertEquals("wt-wt", RegionLocaleSupport.duckDuckGoRegion("GLOBAL", SearchRoute.GLOBAL));
        assertEquals("wt-wt", RegionLocaleSupport.duckDuckGoRegion("wt-wt", SearchRoute.GLOBAL));
    }
}
