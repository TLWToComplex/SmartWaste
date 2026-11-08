package com.smartwaste.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rag")
public record RagProperties(String internalToken, String dataDir, String esUrl, String esApiKey,
        String esIndex, String chromaUrl, String chromaToken, String chromaTenant,
        String chromaDatabase, String chromaCollection, String rerankUrl, String rerankKey,
        String rerankModel, double rerankMinScore, double vectorMaxDistance) { }
