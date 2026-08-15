package io.github.changlu.openreach.search;

import io.github.changlu.openreach.common.BadRequestException;

import java.util.Locale;

/**
 * Normalized time range for Web search.
 *
 * <p>The public API accepts readable values such as {@code day/week/month/year}
 * and common aliases used by search products ({@code d/w/m/y},
 * {@code qdr:d/qdr:w/qdr:m/qdr:y}, {@code pd/pw/pm/py}). Providers only receive
 * this normalized enum, so upstream-specific parameter syntax stays out of the
 * controller/service layer.</p>
 */
public enum SearchTimeRange {
    ANY("any"),
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
    YEAR("year");

    private final String apiValue;

    SearchTimeRange(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }

    public boolean isRestricted() {
        return this != ANY;
    }

    public static SearchTimeRange parse(String raw) {
        if (raw == null || raw.isBlank()) return ANY;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "any", "all", "none", "off", "0" -> ANY;
            case "day", "d", "1d", "past_day", "pd", "qdr:d" -> DAY;
            case "week", "w", "1w", "past_week", "pw", "qdr:w" -> WEEK;
            case "month", "m", "1m", "past_month", "pm", "qdr:m" -> MONTH;
            case "year", "y", "1y", "past_year", "py", "qdr:y" -> YEAR;
            default -> throw new BadRequestException(
                    "Unsupported timeRange: " + raw + ". Supported: any,day,week,month,year");
        };
    }
}
