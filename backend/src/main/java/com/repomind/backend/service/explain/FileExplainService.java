package com.repomind.backend.service.explain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.backend.domain.repo.FileNode;
import com.repomind.backend.domain.repo.FileNodeRepository;
import com.repomind.backend.domain.repo.Repo;
import com.repomind.backend.domain.repo.RepoRepository;
import com.repomind.backend.domain.user.User;
import com.repomind.backend.domain.user.UserRepository;
import com.repomind.backend.service.ai.AiProviderRouter;
import com.repomind.backend.service.ai.dto.AiGenerationRequest;
import com.repomind.backend.service.ai.dto.AiGenerationResponse;
import com.repomind.backend.service.retrieval.GitHubContentClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class FileExplainService {

    private static final Logger log = LoggerFactory.getLogger(FileExplainService.class);
    private static final int MAX_CONTENT_CHARS = 6000;

    private static final String SYSTEM_PROMPT = """
            You are a senior software architect. Analyze the source code deeply and produce a DETAILED, COMPREHENSIVE Mermaid diagram capturing every significant element.

            CRITICAL: Respond ONLY with a single valid JSON object. No markdown fences, no explanation, no text outside the JSON.

            Required JSON format:
            {"diagramType":"<type>","mermaidCode":"<mermaid code with \\\\n for line breaks>","summary":"<two sentences describing the file>","concepts":["<c1>","<c2>","<c3>","<c4>","<c5>"]}

            Diagram type selection:
            - Controller / API / Handler → "sequenceDiagram" — show ALL participants, every method call, service/repo calls, DB interactions, error paths, and response flow
            - Service / Business logic → "flowchart" — show EVERY method, decision branch, condition, loop, error handling path, and data transformation
            - Entity / Model / DTO → "classDiagram" — show ALL fields with exact types, ALL methods with return types, and relationships (inheritance, composition, association) to other classes
            - Config / Client / Utility → "flowchart" — show all properties, dependencies, initialization steps, and how each component connects

            Mermaid syntax rules (STRICT — invalid syntax causes rendering failure):
            - flowchart TD: nodes A[Label], decisions A{Condition}, ovals A((Start)), arrows -->
            - sequenceDiagram: participant declarations first, use ->> and -->>, Note over X: text
            - EVERY statement must fit on ONE single line. NEVER break a Note, message label, or
              node label across lines — a real newline inside a statement makes the parser fail.
              If a Note needs multiple facts, separate them with commas on the same line.
            - classDiagram: fields as "+fieldName Type" (NO colon), methods as "+methodName(paramName Type) ReturnType" (NO colon after param or after closing paren)
              WRONG:  +id: UUID       CORRECT: +id UUID
              WRONG:  +find(id: UUID): User   CORRECT: +find(id UUID) User
            - Node / label text must NEVER contain: " (double-quote), [ ] { } ( ) : (colon) — use only plain words and spaces
            - flowchart arrows with labels use -->|label| not -->|label with colons|
            - Target 15 to 25 nodes — include ALL methods, fields, conditions, dependencies, and data flows visible in the code
            - Newlines in JSON string: use \\n (literal backslash-n, never a real newline character)
            """;

    private final FileNodeRepository fileNodeRepository;
    private final RepoRepository repoRepository;
    private final UserRepository userRepository;
    private final GitHubContentClient gitHubContentClient;
    private final AiProviderRouter aiProviderRouter;
    private final ObjectMapper objectMapper;

    public FileExplainService(
            FileNodeRepository fileNodeRepository,
            RepoRepository repoRepository,
            UserRepository userRepository,
            GitHubContentClient gitHubContentClient,
            AiProviderRouter aiProviderRouter,
            ObjectMapper objectMapper) {
        this.fileNodeRepository = fileNodeRepository;
        this.repoRepository = repoRepository;
        this.userRepository = userRepository;
        this.gitHubContentClient = gitHubContentClient;
        this.aiProviderRouter = aiProviderRouter;
        this.objectMapper = objectMapper;
    }

    public FileExplainResponse explain(UUID repoId, UUID fileId, String providerInput) {
        FileNode file = fileNodeRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));

        Repo repo = repoRepository.findById(repoId)
                .orElseThrow(() -> new IllegalArgumentException("Repo not found: " + repoId));

        User user = userRepository.findById(repo.getUser().getId())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String content = gitHubContentClient.fetchFileContent(repo, user, file.getPath())
                .map(c -> c.length() > MAX_CONTENT_CHARS ? c.substring(0, MAX_CONTENT_CHARS) : c)
                .orElse("");

        log.info("[explain] file={} contentLen={} repo={}", file.getPath(), content.length(), repo.getName());

        String language = file.getLanguage() != null ? file.getLanguage() : inferLanguage(file.getPath());
        String roleSummary = file.getRoleSummary() != null ? file.getRoleSummary() : inferRole(file.getPath());
        String provider = resolveProvider(providerInput);

        ExplainPayload payload;
        if (content.isBlank()) {
            log.warn("[explain] GitHub returned empty content for path={} — using fallback diagram", file.getPath());
            payload = fallback(file.getName());
        } else {
            String userPrompt = buildUserPrompt(file.getPath(), language, roleSummary, content);
            payload = generateWithFailover(provider, userPrompt, file);
        }

        return new FileExplainResponse(
                file.getId().toString(),
                file.getPath(),
                file.getName(),
                language,
                file.getSizeBytes(),
                roleSummary,
                payload.diagramType(),
                payload.mermaidCode(),
                payload.summary(),
                payload.concepts()
        );
    }

    // Tried in order when the requested provider fails (rate limit, network, bad JSON).
    // Returning a real error beats a fake generic diagram: the frontend caches successful
    // responses per file, so a fake diagram would stick even after the provider recovers.
    private static final List<String> PROVIDER_FAILOVER_ORDER = List.of("GROQ", "NVIDIA_DEV", "GEMINI");

    private ExplainPayload generateWithFailover(String requestedProvider, String userPrompt, FileNode file) {
        List<String> candidates = new java.util.ArrayList<>();
        candidates.add(requestedProvider);
        PROVIDER_FAILOVER_ORDER.stream()
                .filter(p -> !p.equals(requestedProvider))
                .forEach(candidates::add);

        Exception lastFailure = null;
        for (String candidate : candidates) {
            try {
                String model = modelForProvider(candidate);
                log.info("[explain] calling LLM provider={} model={}", candidate, model);
                AiGenerationResponse aiResponse = aiProviderRouter.resolve(candidate)
                        .generate(new AiGenerationRequest(candidate, model, SYSTEM_PROMPT, userPrompt, 0.1, 4000));
                log.info("[explain] LLM responded provider={} rawLen={}", candidate, aiResponse.text().length());
                log.debug("[explain] raw LLM response: {}", aiResponse.text());
                ExplainPayload parsed = parsePayload(aiResponse.text(), file.getName());
                if (parsed != null) {
                    return parsed;
                }
                log.warn("[explain] provider={} returned an unparseable diagram for file={} — trying next provider",
                        candidate, file.getPath());
            } catch (Exception ex) {
                lastFailure = ex;
                log.warn("[explain] provider={} failed for file={}: {} — trying next provider",
                        candidate, file.getPath(), ex.getMessage());
            }
        }
        throw new IllegalStateException(
                "Diagram generation failed — all AI providers are unavailable or rate limited. Try again shortly.",
                lastFailure);
    }

    private String buildUserPrompt(String path, String language, String role, String content) {
        return """
                Explain this file using a Mermaid diagram and return only JSON.

                Path: %s
                Language: %s
                Role: %s

                Content:
                ---
                %s
                ---
                """.formatted(path, language, role, content);
    }

    @SuppressWarnings("unchecked")
    private ExplainPayload parsePayload(String text, String fileName) {
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
                log.warn("[explain] LLM returned empty mermaidCode for file={}", fileName);
                return null;
            }
            log.info("[explain] diagram parsed ok diagramType={} conceptCount={}", diagramType, concepts.size());
            return new ExplainPayload(diagramType, mermaidCode, summary, concepts);
        } catch (Exception ex) {
            log.error("[explain] JSON parse failed for file={}: {} — raw snippet: {}", fileName, ex.getMessage(),
                    text.length() > 300 ? text.substring(0, 300) : text);
            return null;
        }
    }

    private ExplainPayload fallback(String fileName) {
        String safe = fileName.replaceAll("[\"\\[\\]]", "");
        return new ExplainPayload(
                "flowchart",
                "flowchart TD\n    A[" + safe + "] --> B[Core Logic]\n    B --> C[Output]",
                "Diagram could not be generated — review the source file directly.",
                List.of("File: " + fileName)
        );
    }

    private String resolveProvider(String input) {
        return (input == null || input.isBlank()) ? "NVIDIA_DEV" : input.trim().toUpperCase();
    }

    private String stringVal(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    private String inferLanguage(String path) {
        if (path == null) return "Unknown";
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "Unknown";
        return switch (path.substring(dot + 1).toLowerCase()) {
            case "java" -> "Java";
            case "js", "jsx" -> "JavaScript";
            case "ts", "tsx" -> "TypeScript";
            case "py" -> "Python";
            case "go" -> "Go";
            case "rs" -> "Rust";
            case "c", "cpp", "h", "hpp" -> "C/C++";
            case "sql" -> "SQL";
            case "yml", "yaml" -> "YAML";
            case "json" -> "JSON";
            case "md" -> "Markdown";
            default -> "Unknown";
        };
    }

    private String inferRole(String path) {
        if (path == null) return "Application file";
        String lower = path.toLowerCase();
        if (lower.contains("controller")) return "API entry and request routing";
        if (lower.contains("service")) return "Business rules and orchestration";
        if (lower.contains("repository")) return "Persistence layer access";
        if (lower.contains("config")) return "Runtime configuration";
        if (lower.contains("handler")) return "Request handler";
        if (lower.contains("entity") || lower.contains("model")) return "Data model";
        if (lower.contains("dto")) return "Data transfer object";
        return "Application implementation file";
    }

    private String modelForProvider(String provider) {
        if (provider.contains("GEMINI")) return "gemini-2.0-flash";
        if (provider.contains("GROQ")) return "openai/gpt-oss-120b";
        return "meta/llama-3.1-8b-instruct";
    }

    private record ExplainPayload(
            String diagramType,
            String mermaidCode,
            String summary,
            List<String> concepts
    ) {}
}
