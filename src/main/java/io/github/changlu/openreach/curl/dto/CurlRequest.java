package io.github.changlu.openreach.curl.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Safe public-HTTP request. v0.1.4 intentionally supports read-only GET/HEAD only.
 */
public record CurlRequest(
        @NotBlank @Size(max = 2048) String url,
        @Pattern(regexp = "(?i)GET|HEAD", message = "must be GET or HEAD") String method,
        @Size(max = 16) Map<@Size(max = 64) String, @Size(max = 2048) String> headers,
        @JsonProperty("maxChars") @JsonAlias("max_chars") @Min(1000) @Max(200000) Integer maxChars
) {}
