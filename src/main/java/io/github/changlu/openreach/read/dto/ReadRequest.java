package io.github.changlu.openreach.read.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ReadRequest(
        @NotBlank String url,
        @Min(1000) @Max(200000) Integer maxChars
) {}
