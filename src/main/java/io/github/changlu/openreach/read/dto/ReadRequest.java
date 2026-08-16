package io.github.changlu.openreach.read.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReadRequest(
        @NotBlank @Size(max = 2048) String url,
        @JsonProperty("maxChars") @JsonAlias("max_chars") @Min(1000) @Max(200000) Integer maxChars
) {}
