package io.github.changlu.openreach;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentHubPluginConfigTest {

    @Test
    void pluginRequiresExplicitReachableBaseUrlInsteadOfLocalhostDefault() throws Exception {
        String json = Files.readString(
                Path.of("docs/agenthub/skills/openreach-http-plugin.json"),
                StandardCharsets.UTF_8
        );

        assertTrue(json.contains("\"baseUrl\": \"{{BASE_URL}}\""));
        assertTrue(json.contains("Tool Runner"));
        assertTrue(json.contains("v0.1.4"));
        assertTrue(json.contains("私网"));
        assertTrue(json.contains("HTTP_412"));
        assertTrue(json.contains("/api/web/curl"));
        assertTrue(json.contains("OpenReach 自身"));
        assertFalse(json.contains("\"baseUrl\": \"http://localhost:8080\""));
    }
}
