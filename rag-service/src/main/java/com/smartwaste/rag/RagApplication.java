package com.smartwaste.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@SpringBootApplication
@EnableConfigurationProperties({RagProperties.class, QueryExpansionProperties.class, RetrievalProperties.class})
public class RagApplication {
    public static void main(String[] args) { SpringApplication.run(RagApplication.class, args); }

    @Bean @Scope("prototype")
    RestClient.Builder restClientBuilder() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(45000);
        return RestClient.builder().requestFactory(factory);
    }
}
