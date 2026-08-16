package io.github.changlu.openreach.routing;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralizes route -> provider-chain selection for every web capability.
 * Services execute a resolved chain but do not know which configuration field
 * belongs to CN/GLOBAL, keeping future route expansion out of service logic.
 */
@Component
public class ProviderChainResolver {
    private final WebCapabilityProperties properties;

    public ProviderChainResolver(WebCapabilityProperties properties) {
        this.properties = properties;
    }

    public List<String> searchProviders(SearchRoute route) {
        List<String> configured = route == SearchRoute.GLOBAL
                ? properties.getSearch().getGlobalProviderOrder()
                : properties.getSearch().effectiveCnProviderOrder();
        return copy(configured);
    }

    public List<String> searchProviders(SearchRoute route, boolean requireTimeRange) {
        if (!requireTimeRange) return searchProviders(route);
        List<String> configured = route == SearchRoute.GLOBAL
                ? properties.getSearch().getGlobalTimeRangeProviderOrder()
                : properties.getSearch().getCnTimeRangeProviderOrder();
        if (configured == null || configured.isEmpty()) {
            // Restricted timeRange must never silently fall back to the legacy CN/GLOBAL
            // chain where most providers do not implement native time filtering.
            return route == SearchRoute.GLOBAL
                    ? new ArrayList<>(List.of("bing", "brave", "duckduckgo", "baidu"))
                    : new ArrayList<>(List.of("baidu", "bing", "duckduckgo", "brave"));
        }
        return copy(configured);
    }

    public List<String> imageSearchProviders(SearchRoute route) {
        List<String> configured = route == SearchRoute.GLOBAL
                ? properties.getImageSearch().getGlobalProviderOrder()
                : properties.getImageSearch().effectiveCnProviderOrder();
        return copy(configured);
    }

    private List<String> copy(List<String> value) {
        return value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
}
