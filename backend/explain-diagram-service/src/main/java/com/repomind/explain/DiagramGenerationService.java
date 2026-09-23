package com.repomind.explain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.explain.ai.AiProviderRouter;
import com.repomind.explain.ai.dto.AiGenerationRequest;
import com.repomind.explain.ai.dto.AiGenerationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs one diagram generation across the provider failover chain. If a
 * provider answers with unparseable output, a cheap repair call (no source
 * code, just the broken text) is tried before falling over to the next
 * provider — rescuing near-miss diagrams instead of paying full prompt cost
 * again.
 */
@Service
public class DiagramGenerationService {

    private static final Logger log = LoggerFactory.getLogger(DiagramGenerationService.class);

    // Tried in order when the requested provider fails (rate limit, network, bad JSON).
    // Returning a real error beats a fake generic diagram: the frontend caches successful
    // responses per file, so a fake diagram would stick even after the provider recovers.
    private static final List<String> PROVIDER_FAILOVER_ORDER = List.of("LOCAL", "GROQ", "NVIDIA_DEV", "GEMINI");

    // 7000 leaves ~1000 tokens of headroom under the 8k output limit shared by
    // the free-tier providers. Raising the cap costs nothing when unused —
    // billing is per token generated — but stops detailed diagrams from being
    // silently compressed to fit.
    private static final int MAX_OUTPUT_TOKENS = 7000;

    private static final Set<String> VALID_DIAGRAM_HEADERS = Set.of(
            "flowchart", "graph", "sequenceDiagram", "classDiagram", "stateDiagram-v2", "erDiagram");

    private static final String REPAIR_SYSTEM_PROMPT = """
            You repair malformed diagram output from another model.
            Respond ONLY with one valid JSON object: {"diagramType":"<type>","mermaidCode":"<mermaid>","summary":"<text>","concepts":["..."]}
            Rules: encode newlines inside mermaidCode as \\n; every Mermaid statement on ONE line; \
            classDiagram members use "+name Type" and "+method(param Type) ReturnType" with NO colons; \
            node and label text must not contain " [ ] { } ( ) or colons (:::styleTag suffixes are allowed); \
            preserve the original diagram content — fix only formatting and syntax.
            """;

    private final AiProviderRouter aiProviderRouter;
    private final ObjectMapper objectMapper;

    public DiagramGenerationService(AiProviderRouter aiProviderRouter, ObjectMapper objectMapper) {
        this.aiProviderRouter = aiProviderRouter;
        this.objectMapper = objectMapper;
    }

    public record DiagramPayload(
            String diagramType,
            String mermaidCode,
            String summary,
            List<String> concepts,
            String provider,
            String model
    ) {}

    public DiagramPayload generate(String requestedProvider, String systemPrompt, String userPrompt,
                                   boolean complex, String logContext) {
        List<String> candidates = new ArrayList<>();
        candidates.add(requestedProvider);
        PROVIDER_FAILOVER_ORDER.stream()
                .filter(p -> !p.equals(requestedProvider))
                .forEach(candidates::add);

        Exception lastFailure = null;
        for (String candidate : candidates) {
            try {
                String model = modelForProvider(candidate, complex);
                log.info("[diagram] calling LLM provider={} model={} ctx={}", candidate, model, logContext);
                AiGenerationResponse aiResponse = aiProviderRouter.resolve(candidate)
                        .generate(new AiGenerationRequest(candidate, model, systemPrompt, userPrompt, 0.1, MAX_OUTPUT_TOKENS));
                log.info("[diagram] LLM responded provider={} rawLen={} ctx={}",
                        candidate, aiResponse.text().length(), logContext);
                DiagramPayload parsed = parsePayload(aiResponse.text(), candidate, model, logContext);
                if (parsed != null) {
                    return parsed;
                }
                parsed = repair(candidate, model, aiResponse.text(), logContext);
                if (parsed != null) {
                    return parsed;
                }
                log.warn("[diagram] provider={} returned an unrepairable diagram ctx={} — trying next provider",
                        candidate, logContext);
            } catch (Exception ex) {
                lastFailure = ex;
                log.warn("[diagram] provider={} failed ctx={}: {} — trying next provider",
                        candidate, logContext, ex.getMessage());
            }
        }
        throw new IllegalStateException(
                "Diagram generation failed — all AI providers are unavailable or rate limited. Try again shortly.",
                lastFailure);
    }

    /** One retry with only the broken text — a fraction of the original prompt cost. */
    private DiagramPayload repair(String provider, String model, String brokenText, String logContext) {
        try {
            log.info("[diagram] attempting repair provider={} ctx={}", provider, logContext);
            AiGenerationResponse repaired = aiProviderRouter.resolve(provider)
                    .generate(new AiGenerationRequest(provider, model, REPAIR_SYSTEM_PROMPT,
                            "Broken output to repair:\n" + brokenText, 0.0, MAX_OUTPUT_TOKENS));
            return parsePayload(repaired.text(), provider, model, logContext + ":repaired");
        } catch (Exception ex) {
            log.warn("[diagram] repair failed ctx={}: {}", logContext, ex.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private DiagramPayload parsePayload(String text, String provider, String model, String logContext) {
        try {
            String cleaned = text
                    .replaceAll("(?s)```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    // collapse Java-style string concatenation emitted by some models: "...\n" + "..." → "...\n..."
                    .replaceAll("\"\\s*\\+\\s*\"", "")
                    .trim();
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
            Map<String, Object> map = objectMapper.readValue(cleaned, Map.class);
            String diagramType = stringVal(map.get("diagramType"), "flowchart");
            String mermaidCode = stringVal(map.get("mermaidCode"), "");
            String summary = stringVal(map.get("summary"), "");
            List<String> concepts = map.get("concepts") instanceof List<?> list
                    ? list.stream().map(Object::toString).toList()
                    : List.of();
            if (mermaidCode.isBlank()) {
                log.warn("[diagram] LLM returned empty mermaidCode ctx={}", logContext);
                return null;
            }
            String header = mermaidCode.strip().split("\\s+")[0];
            if (!VALID_DIAGRAM_HEADERS.contains(header)) {
                log.warn("[diagram] invalid Mermaid header '{}' ctx={}", header, logContext);
                return null;
            }
            log.info("[diagram] parsed ok diagramType={} conceptCount={} ctx={}",
                    diagramType, concepts.size(), logContext);
            return new DiagramPayload(diagramType, mermaidCode, summary, concepts, provider, model);
        } catch (Exception ex) {
            log.error("[diagram] JSON parse failed ctx={}: {} — raw snippet: {}", logContext, ex.getMessage(),
                    text.length() > 300 ? text.substring(0, 300) : text);
            return null;
        }
    }

    /**
     * Simple files (DTOs, entities, config) get a small cheap model; files with
     * real control flow get the strong one. Failed calls on a too-weak model
     * cost more than the price difference.
     */
    private String modelForProvider(String provider, boolean complex) {
        if (provider.contains("GEMINI")) {
            return "gemini-2.0-flash";
        }
        if (provider.contains("GROQ")) {
            return complex ? "openai/gpt-oss-120b" : "llama-3.1-8b-instant";
        }
        return complex ? "meta/llama-3.3-70b-instruct" : "meta/llama-3.1-8b-instruct";
    }

    private String stringVal(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }
}
