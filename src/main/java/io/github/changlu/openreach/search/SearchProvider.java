package io.github.changlu.openreach.search;

import io.github.changlu.openreach.common.BadRequestException;
import io.github.changlu.openreach.search.dto.SearchItem;

import java.util.List;

public interface SearchProvider {
    String name();

    /** v1.0.1 compatible SPI. */
    List<SearchItem> search(String query, int limit, String region);

    /**
     * Whether this provider can enforce a real upstream-side time filter.
     * False means callers must not silently pass a restricted time range to it.
     */
    default boolean supportsTimeRange() {
        return false;
    }

    /**
     * Range-aware capability check. Providers that support every restricted range
     * can keep overriding {@link #supportsTimeRange()} only. Providers with partial
     * support (for example Bing Web: day/week/month but not a verified year filter)
     * override this method.
     */
    default boolean supportsTimeRange(SearchTimeRange timeRange) {
        SearchTimeRange normalized = timeRange == null ? SearchTimeRange.ANY : timeRange;
        return !normalized.isRestricted() || supportsTimeRange();
    }

    /**
     * v1.0.2 extension point. Existing third-party providers continue compiling
     * because the legacy method remains the only abstract contract.
     */
    default List<SearchItem> search(String query, int limit, String region, SearchTimeRange timeRange) {
        SearchTimeRange normalized = timeRange == null ? SearchTimeRange.ANY : timeRange;
        if (normalized.isRestricted() && !supportsTimeRange(normalized)) {
            throw new BadRequestException("Search provider '" + name()
                    + "' does not support timeRange=" + normalized.apiValue());
        }
        return search(query, limit, region);
    }
}
