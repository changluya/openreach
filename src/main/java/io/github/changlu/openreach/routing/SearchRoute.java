package io.github.changlu.openreach.routing;

import java.util.Locale;

public enum SearchRoute {
    CN,
    GLOBAL;

    public static SearchRoute fromConfig(String value) {
        if (value == null || value.isBlank()) return CN;
        try {
            return SearchRoute.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return CN;
        }
    }
}
