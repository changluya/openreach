package io.github.changlu.openreach.routing;

import java.util.Locale;
import java.util.Map;

public final class RegionLocaleSupport {
    private static final Map<String, String> LANGUAGE_BY_COUNTRY = Map.ofEntries(
            Map.entry("US", "en-US"), Map.entry("GB", "en-GB"), Map.entry("SG", "en-SG"),
            Map.entry("AU", "en-AU"), Map.entry("CA", "en-CA"), Map.entry("DE", "de-DE"),
            Map.entry("FR", "fr-FR"), Map.entry("JP", "ja-JP"), Map.entry("KR", "ko-KR"),
            Map.entry("BR", "pt-BR"), Map.entry("ES", "es-ES"), Map.entry("IT", "it-IT"),
            Map.entry("IN", "en-IN"), Map.entry("CN", "zh-CN")
    );

    private RegionLocaleSupport() {}

    public static String countryCode(String region, SearchRoute route) {
        if (route == SearchRoute.CN) return "CN";
        if (region == null || region.isBlank() || "auto".equalsIgnoreCase(region)
                || "global".equalsIgnoreCase(region) || "wt-wt".equalsIgnoreCase(region)) {
            return "US";
        }
        String normalized = region.trim().replace('_', '-');
        String[] parts = normalized.split("-");
        String candidate = parts[parts.length - 1].toUpperCase(Locale.ROOT);
        if (candidate.length() == 2 && candidate.chars().allMatch(Character::isLetter)) return candidate;
        String upper = normalized.toUpperCase(Locale.ROOT);
        return upper.length() == 2 ? upper : "US";
    }

    public static String localeTag(String region, SearchRoute route) {
        if (route == SearchRoute.CN) return "zh-CN";
        if (region != null && !region.isBlank()) {
            String normalized = region.trim().replace('_', '-');
            if (normalized.matches("(?i)^[a-z]{2,3}-[a-z]{2}$")) {
                String[] parts = normalized.split("-");
                return parts[0].toLowerCase(Locale.ROOT) + "-" + parts[1].toUpperCase(Locale.ROOT);
            }
        }
        return LANGUAGE_BY_COUNTRY.getOrDefault(countryCode(region, route), "en-US");
    }

    public static String acceptLanguage(String region, SearchRoute route) {
        String tag = localeTag(region, route);
        String language = tag.substring(0, tag.indexOf('-'));
        if (route == SearchRoute.CN) return "zh-CN,zh;q=0.9,en;q=0.6";
        if ("en".equals(language)) return tag + ",en;q=0.9";
        return tag + "," + language + ";q=0.9,en;q=0.6";
    }

    public static String duckDuckGoRegion(String region, SearchRoute route) {
        if (route == SearchRoute.CN) return "cn-zh";
        if (region == null || region.isBlank() || "auto".equalsIgnoreCase(region)
                || "global".equalsIgnoreCase(region) || "wt-wt".equalsIgnoreCase(region)) {
            return "wt-wt";
        }
        String tag = localeTag(region, route);
        String[] parts = tag.split("-");
        return parts[0].toLowerCase(Locale.ROOT) + "-" + parts[1].toLowerCase(Locale.ROOT);
    }
}
