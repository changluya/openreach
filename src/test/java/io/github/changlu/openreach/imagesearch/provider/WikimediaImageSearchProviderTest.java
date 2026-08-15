package io.github.changlu.openreach.imagesearch.provider;

import io.github.changlu.openreach.config.WebCapabilityProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WikimediaImageSearchProviderTest {
    @Test
    void parsesImageInfoAndLicenseMetadata() {
        String json = """
                {
                  "query": {
                    "pages": [{
                      "pageid": 1,
                      "title": "File:Golden Gate Bridge.jpg",
                      "imageinfo": [{
                        "url": "https://upload.wikimedia.org/example/bridge.jpg",
                        "descriptionurl": "https://commons.wikimedia.org/wiki/File:Golden_Gate_Bridge.jpg",
                        "thumburl": "https://upload.wikimedia.org/example/640px-bridge.jpg",
                        "width": 2400,
                        "height": 1600,
                        "mime": "image/jpeg",
                        "extmetadata": {
                          "ObjectName": {"value": "Golden Gate Bridge"},
                          "LicenseShortName": {"value": "CC BY-SA 4.0"},
                          "LicenseUrl": {"value": "https://creativecommons.org/licenses/by-sa/4.0/"}
                        }
                      }]
                    }]
                  }
                }
                """;
        var provider = new WikimediaImageSearchProvider(null, new WebCapabilityProperties(), JsonMapper.builder().build());
        var items = provider.parseResults(json, 5);
        assertEquals(1, items.size());
        assertEquals("Golden Gate Bridge", items.get(0).title());
        assertEquals("wikimedia", items.get(0).provider());
        assertEquals("jpg", items.get(0).imageFormat());
        assertEquals("CC BY-SA 4.0", items.get(0).license());
        assertEquals(2400, items.get(0).width());
        assertEquals("commons.wikimedia.org", items.get(0).domain());
    }

    @Test
    void skipsPagesWithoutImageInfoAndAllowsMissingLicense() {
        String json = """
                {"query":{"pages":[
                  {"title":"File:Missing.jpg"},
                  {"title":"File:Photo.png","imageinfo":[{
                    "url":"https://upload.wikimedia.org/photo.png",
                    "descriptionurl":"https://commons.wikimedia.org/wiki/File:Photo.png",
                    "mime":"image/png"
                  }]}
                ]}}
                """;
        var provider = new WikimediaImageSearchProvider(null, new WebCapabilityProperties(), JsonMapper.builder().build());
        var items = provider.parseResults(json, 5);
        assertEquals(1, items.size());
        assertEquals("Photo.png", items.get(0).title());
        assertEquals("png", items.get(0).imageFormat());
        assertNull(items.get(0).license());
    }
}
