package io.github.changlu.openreach.routing;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class SearchRouteResolver {
    private static final Set<String> CN_ALIASES = Set.of(
            "cn", "zh-cn", "zh_cn", "cn-zh", "zh-hans-cn", "china"
    );

    private final WebCapabilityProperties properties;

    public SearchRouteResolver(WebCapabilityProperties properties) {
        this.properties = properties;
    }

    /**
     * region remains an external search hint.  This resolver only turns it into an
     * internal routing decision.  Provider-specific locale handling happens later
     * and must not reimplement the CN/GLOBAL decision.
     */
    public SearchRoute resolve(String region) {
        if (region == null || region.isBlank() || "auto".equalsIgnoreCase(region)) {
            return SearchRoute.fromConfig(properties.getRouting().getDefaultRoute());
        }
        String normalized = region.trim().toLowerCase(Locale.ROOT);
        return CN_ALIASES.contains(normalized) ? SearchRoute.CN : SearchRoute.GLOBAL;
    }
}
