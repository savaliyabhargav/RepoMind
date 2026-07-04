package com.repomind.backend.service.ai;

import com.repomind.backend.service.ai.dto.AiEmbeddingRequest;
import com.repomind.backend.service.ai.dto.AiEmbeddingResponse;
import com.repomind.backend.service.ai.dto.AiGenerationRequest;
import com.repomind.backend.service.ai.dto.AiGenerationResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AiProviderRouterTest {

    // Simple stub that supports a fixed provider name
    private static AiProviderClient stubFor(String supportedProvider) {
        return new AiProviderClient() {
            @Override public boolean supports(String p) { return supportedProvider.equalsIgnoreCase(p); }
            @Override public AiGenerationResponse generate(AiGenerationRequest r) { return null; }
            @Override public AiEmbeddingResponse embed(AiEmbeddingRequest r) { return null; }
        };
    }

    // ── resolve ───────────────────────────────────────────────────────────────

    @Test
    void resolve_returnsCorrectClientForGroq() {
        AiProviderClient groq = stubFor("GROQ");
        AiProviderRouter router = new AiProviderRouter(List.of(groq, stubFor("GEMINI")));

        assertThat(router.resolve("GROQ")).isSameAs(groq);
    }

    @Test
    void resolve_returnsCorrectClientForGemini() {
        AiProviderClient gemini = stubFor("GEMINI");
        AiProviderRouter router = new AiProviderRouter(List.of(stubFor("GROQ"), gemini));

        assertThat(router.resolve("GEMINI")).isSameAs(gemini);
    }

    @Test
    void resolve_returnsFirstMatchWhenMultipleSupport() {
        AiProviderClient first = stubFor("GROQ");
        AiProviderClient second = stubFor("GROQ");
        AiProviderRouter router = new AiProviderRouter(List.of(first, second));

        assertThat(router.resolve("GROQ")).isSameAs(first);
    }

    @Test
    void resolve_throwsIllegalArgumentForUnknownProvider() {
        AiProviderRouter router = new AiProviderRouter(List.of(stubFor("GROQ"), stubFor("GEMINI")));

        assertThatThrownBy(() -> router.resolve("UNKNOWN_AI"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN_AI");
    }

    @Test
    void resolve_throwsIllegalArgumentWhenClientListIsEmpty() {
        AiProviderRouter router = new AiProviderRouter(List.of());

        assertThatThrownBy(() -> router.resolve("GROQ"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolve_throwsIllegalArgumentForNullProvider() {
        AiProviderRouter router = new AiProviderRouter(List.of(stubFor("GROQ")));

        // The stub will receive null and return false; router throws
        assertThatThrownBy(() -> router.resolve(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
