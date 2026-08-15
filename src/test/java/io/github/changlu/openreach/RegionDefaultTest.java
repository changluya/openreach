package io.github.changlu.openreach;

import io.github.changlu.openreach.imagesearch.dto.ImageSearchRequest;
import io.github.changlu.openreach.search.dto.SearchRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionDefaultTest {
    @Test
    void searchRegionDefaultsToAuto() {
        assertEquals("auto", new SearchRequest("test", 5, null, "auto").effectiveRegion());
        assertEquals("auto", new SearchRequest("test", 5, "", "auto").effectiveRegion());
    }

    @Test
    void imageSearchRegionDefaultsToAuto() {
        assertEquals("auto", new ImageSearchRequest("test", 5, null, "auto").effectiveRegion());
    }
}
