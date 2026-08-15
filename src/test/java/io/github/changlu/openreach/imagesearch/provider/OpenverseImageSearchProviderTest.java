package io.github.changlu.openreach.imagesearch.provider;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenverseImageSearchProviderTest {
    @Test
    void parsesLicenseMetadata() {
        String json = """
                {"results":[{"title":"Open Cat","url":"https://img.example/open.jpg","thumbnail":"https://thumb.example/open.jpg","foreign_landing_url":"https://commons.example/page","source":"wikimedia","width":1024,"height":768,"license":"by-sa","license_url":"https://creativecommons.org/licenses/by-sa/4.0/"}]}
                """;
        var provider = new OpenverseImageSearchProvider(null, new WebCapabilityProperties(), JsonMapper.builder().build());
        var items = provider.parseResults(json, 5);
        assertEquals(1, items.size());
        assertEquals("openverse", items.get(0).provider());
        assertEquals("by-sa", items.get(0).license());
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0/", items.get(0).licenseUrl());
    }
}
