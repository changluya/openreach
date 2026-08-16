package io.github.changlu.openreach.routing;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderChainResolverTest {

    @Test
    void resolvesIndependentSearchChainsAndKeepsLegacyCnFallback() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setProviderOrder(List.of("legacy-cn"));
        props.getSearch().setCnProviderOrder(List.of());
        props.getSearch().setGlobalProviderOrder(List.of("global-a", "global-b"));
        ProviderChainResolver resolver = new ProviderChainResolver(props);

        assertEquals(List.of("legacy-cn"), resolver.searchProviders(SearchRoute.CN));
        assertEquals(List.of("global-a", "global-b"), resolver.searchProviders(SearchRoute.GLOBAL));
        assertEquals(List.of("duckduckgo", "brave"), resolver.searchProviders(SearchRoute.CN, true));
        assertEquals(List.of("brave", "duckduckgo"), resolver.searchProviders(SearchRoute.GLOBAL, true));

        props.getSearch().setCnProviderOrder(List.of("new-cn"));
        assertEquals(List.of("new-cn"), resolver.searchProviders(SearchRoute.CN));
        props.getSearch().setCnTimeRangeProviderOrder(List.of());
        assertEquals(List.of("new-cn"), resolver.searchProviders(SearchRoute.CN, true));
    }

    @Test
    void resolvesImageChainsIndependentlyFromWebChains() {
        WebCapabilityProperties props = new WebCapabilityProperties();
        props.getSearch().setGlobalProviderOrder(List.of("brave"));
        props.getImageSearch().setProviderOrder(List.of("legacy-image"));
        props.getImageSearch().setCnProviderOrder(List.of());
        props.getImageSearch().setGlobalProviderOrder(List.of("wikimedia"));
        ProviderChainResolver resolver = new ProviderChainResolver(props);

        assertEquals(List.of("legacy-image"), resolver.imageSearchProviders(SearchRoute.CN));
        assertEquals(List.of("wikimedia"), resolver.imageSearchProviders(SearchRoute.GLOBAL));
        assertEquals(List.of("brave"), resolver.searchProviders(SearchRoute.GLOBAL));
    }
}
