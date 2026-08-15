package io.github.changlu.openreach;

import io.github.changlu.openreach.web.WebCapabilityController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebCapabilityControllerHealthTest {
    @Test
    void healthDoesNotRequireUpstreamProviders() {
        var controller = new WebCapabilityController(null, null, null);
        var result = controller.health();
        assertEquals("UP", result.get("status"));
        assertEquals("openreach", result.get("service"));
    }
}
