package io.github.changlu.openreach.routing;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchRouteResolverTest {
    @Test
    void autoUsesConfiguredDefaultRouteAndDefaultsToCn() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        SearchRouteResolver resolver = new SearchRouteResolver(props);
        assertEquals(SearchRoute.CN, resolver.resolve(null));
        assertEquals(SearchRoute.CN, resolver.resolve(""));
        assertEquals(SearchRoute.CN, resolver.resolve("auto"));

        props.getRouting().setDefaultRoute("global");
        assertEquals(SearchRoute.GLOBAL, resolver.resolve("auto"));
    }

    @Test
    void cnAliasesAlwaysResolveToCn() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        SearchRouteResolver resolver = new SearchRouteResolver(props);
        assertEquals(SearchRoute.CN, resolver.resolve("CN"));
        assertEquals(SearchRoute.CN, resolver.resolve("zh-CN"));
        assertEquals(SearchRoute.CN, resolver.resolve("zh_CN"));
        assertEquals(SearchRoute.CN, resolver.resolve("cn-zh"));
        assertEquals(SearchRoute.CN, resolver.resolve("zh-Hans-CN"));
        assertEquals(SearchRoute.CN, resolver.resolve("china"));
    }

    @Test
    void everyExplicitNonCnRegionRoutesGlobal() {
        SearchRouteResolver resolver = new SearchRouteResolver(new WebCapabilityProperties());
        assertEquals(SearchRoute.GLOBAL, resolver.resolve("US"));
        assertEquals(SearchRoute.GLOBAL, resolver.resolve("en-US"));
        assertEquals(SearchRoute.GLOBAL, resolver.resolve("SG"));
        assertEquals(SearchRoute.GLOBAL, resolver.resolve("JP"));
        assertEquals(SearchRoute.GLOBAL, resolver.resolve("GLOBAL"));
        assertEquals(SearchRoute.GLOBAL, resolver.resolve("wt-wt"));
    }

    @Test
    void invalidDefaultRouteFailsSafeToCn() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getRouting().setDefaultRoute("unexpected");
        assertEquals(SearchRoute.CN, new SearchRouteResolver(props).resolve("auto"));
    }
}
