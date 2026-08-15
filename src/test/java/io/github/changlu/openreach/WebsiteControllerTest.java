package io.github.changlu.openreach;

import io.github.changlu.openreach.web.WebsiteController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebsiteControllerTest {

    private final WebsiteController controller = new WebsiteController();

    @Test
    void docsDirectoryRouteForwardsToStaticIndex() {
        assertEquals("forward:/docs/index.html", controller.docs());
    }

    @Test
    void communityRouteRedirectsToDocsCommunitySection() {
        assertEquals("redirect:/docs/#community", controller.community());
    }
}
