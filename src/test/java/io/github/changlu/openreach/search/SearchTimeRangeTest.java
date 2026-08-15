package io.github.changlu.openreach.search;

import io.github.changlu.openreach.common.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchTimeRangeTest {

    @Test
    void normalizesReadableAndCommonSearchAliases() {
        assertEquals(SearchTimeRange.ANY, SearchTimeRange.parse(null));
        assertEquals(SearchTimeRange.ANY, SearchTimeRange.parse("any"));
        assertEquals(SearchTimeRange.DAY, SearchTimeRange.parse("day"));
        assertEquals(SearchTimeRange.DAY, SearchTimeRange.parse("pd"));
        assertEquals(SearchTimeRange.DAY, SearchTimeRange.parse("qdr:d"));
        assertEquals(SearchTimeRange.WEEK, SearchTimeRange.parse("w"));
        assertEquals(SearchTimeRange.MONTH, SearchTimeRange.parse("pm"));
        assertEquals(SearchTimeRange.YEAR, SearchTimeRange.parse("qdr:y"));
    }

    @Test
    void rejectsUnknownTimeRangeInsteadOfSilentlyIgnoringIt() {
        assertThrows(BadRequestException.class, () -> SearchTimeRange.parse("last_3_hours"));
    }
}
