package com.smartwaste.rag;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("rag.retrieval")
public record RetrievalProperties(@Min(1) @Max(30) int perQueryTopK,
        @Min(10) @Max(100) int candidateLimit,
        @Min(1) @Max(10) int evidenceLimit) { }
