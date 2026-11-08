package com.smartwaste.rag;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag.query")
public record QueryExpansionProperties(boolean enabled, @NotBlank String model,
        @NotBlank String baseUrl, String apiKey,
        @Min(1) @Max(5) int maxQueries,
        @Min(1000) @Max(30000) int timeoutMillis,
        @Min(128) @Max(2048) int maxTokens) { }
