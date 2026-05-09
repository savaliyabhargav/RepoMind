package com.repomind.backend.service.ai;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiProviderRouter {

    private final List<AiProviderClient> clients;

    public AiProviderRouter(List<AiProviderClient> clients) {
        this.clients = clients;
    }

    public AiProviderClient resolve(String provider) {
        return clients.stream()
                .filter(client -> client.supports(provider))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No AI provider client configured for provider: " + provider));
    }
}
