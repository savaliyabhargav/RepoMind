package com.repomind.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        // We call WebClient.builder() directly instead of asking Spring to inject it.
        // This works perfectly in both Servlet (Tomcat) and Reactive environments.
        return WebClient.builder().build();
    }
}