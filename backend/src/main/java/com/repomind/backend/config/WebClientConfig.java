package com.repomind.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        // Exposing the Builder allows different services (like GitHubApiClient)
        // to create their own customized WebClient instances with different base URLs.
        return WebClient.builder();
    }

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        // This keeps your existing 'webClient' bean working exactly as before
        // for any other services that are already using it.
        return builder.build();
    }
}