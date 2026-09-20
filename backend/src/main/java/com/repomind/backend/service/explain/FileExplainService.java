package com.repomind.backend.service.explain;

import com.repomind.backend.domain.repo.CanonicalRepo;
import com.repomind.backend.domain.repo.FileNode;
import com.repomind.backend.domain.repo.FileNodeRepository;
import com.repomind.backend.domain.repo.Repo;
import com.repomind.backend.domain.repo.RepoRepository;
import com.repomind.backend.domain.user.User;
import com.repomind.backend.domain.user.UserRepository;
import com.repomind.backend.service.explain.DiagramGenerationService.DiagramPayload;
import com.repomind.backend.service.retrieval.GitHubContentClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional(readOnly = true)
public class FileExplainService {

    private static final Logger log = LoggerFactory.getLogger(FileExplainService.class);

    // Post-condense budget (~6k tokens). Condensing + skeletonizing replaces the
    // old blind substring cut that chopped files mid-method.
    private static final int MAX_PROMPT_CODE_CHARS = 24_000;
    private static final int MAX_RELATED_FILES = 8;

    private static final String SYSTEM_PROMPT = """
            You are a senior software architect. Analyze the source code and produce an ACCURATE Mermaid diagram of its real structure and flow. Never invent methods, fields, or calls that are not in the code.

            CRITICAL: Respond ONLY with a single valid JSON object. No markdown fences, no explanation, no text outside the JSON.

            Required JSON format:
            {"diagramType":"<type>","mermaidCode":"<mermaid code with \\\\n for line breaks>","summary":"<two sentences describing the file>","concepts":["<c1>","<c2>","<c3>","<c4>","<c5>"]}

            Diagram type selection:
            - Controller / API / Handler → "sequenceDiagram" — participants, method calls, service/repo calls, DB interactions, error paths, response flow
            - Service / Business logic → "flowchart" — methods, decision branches, conditions, loops, error handling, data transformations
            - Entity / Model / DTO → "classDiagram" — fields with exact types, methods with return types, relationships (inheritance, composition, association)
            - Config / Client / Utility → "flowchart" — properties, dependencies, initialization steps, component connections

            Detail policy:
            - Diagram the PRIMARY flow in full detail: every public method, decision branch, error path, and external interaction (DB, network, queue).
            - Group small private helpers into ONE subgraph labeled Helpers instead of omitting them.
            - Scale node count with the file: small files 8-15 nodes, large files 20-35. Never pad a trivial file, never flatten a complex one.
            - If the provided code has elided bodies (marked ...), diagram only what is visible — do not guess hidden logic.
            - When Related files are listed, use their real names as participants/nodes instead of inventing generic ones.

            Styling (flowchart only):
            - Tag nodes by appending a style class: :::entry (public entry points), :::db (database or repository access), :::external (network or third-party calls), :::errorpath (error handling), :::helper (helper nodes).
            - Do NOT emit classDef lines — the renderer defines these classes.

            Mermaid syntax rules (STRICT — invalid syntax causes rendering failure):
            - flowchart TD: nodes A[Label], decisions A{Condition}, ovals A((Start)), arrows -->
            - sequenceDiagram: participant declarations first, use ->> and -->>, Note over X: text
            - EVERY statement must fit on ONE single line. NEVER break a Note, message label, or
              node label across lines — a real newline inside a statement makes the parser fail.
              If a Note needs multiple facts, separate them with commas on the same line.
            - classDiagram: fields as "+fieldName Type" (NO colon), methods as "+methodName(paramName Type) ReturnType" (NO colon after param or after closing paren)
              WRONG:  +id: UUID       CORRECT: +id UUID
              WRONG:  +find(id: UUID): User   CORRECT: +find(id UUID) User
            - Node / label text must NEVER contain: " (double-quote), [ ] { } ( ) : (colon) — use only plain words and spaces. The :::styleTag suffix after a node is the ONLY allowed colon usage.
            - flowchart arrows with labels use -->|label| not -->|label with colons|
            - Newlines in JSON string: use \\n (literal backslash-n, never a real newline character)
            """;

    // import extraction across the main languages we ingest
    private static final Pattern JAVA_IMPORT = Pattern.compile("import\\s+(?:static\\s+)?[\\w.]+\\.(\\w+)\\s*;");
    private static final Pattern JS_IMPORT = Pattern.compile("(?:from|require\\()\\s*['\"][^'\"]*?([\\w.-]+?)(?:\\.[jt]sx?)?['\"]");
    private static final Pattern PY_IMPORT = Pattern.compile("(?m)^(?:from\\s+[\\w.]*?(\\w+)\\s+import|import\\s+[\\w.]*?(\\w+)\\s*$)");

    private final FileNodeRepository fileNodeRepository;
    private final RepoRepository repoRepository;
    private final UserRepository userRepository;
    private final GitHubContentClient gitHubContentClient;
    private final DiagramGenerationService diagramGenerationService;
    private final ExplainCacheService explainCacheService;

    public FileExplainService(
            FileNodeRepository fileNodeRepository,
            RepoRepository repoRepository,
            UserRepository userRepository,
            GitHubContentClient gitHubContentClient,
            DiagramGenerationService diagramGenerationService,
            ExplainCacheService explainCacheService) {
        this.fileNodeRepository = fileNodeRepository;
        this.repoRepository = repoRepository;
        this.userRepository = userRepository;
        this.gitHubContentClient = gitHubContentClient;
        this.diagramGenerationService = diagramGenerationService;
        this.explainCacheService = explainCacheService;
    }

    public FileExplainResponse explain(UUID repoId, UUID fileId, String providerInput, boolean refresh) {
        FileNode file = fileNodeRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + fileId));

        Repo repo = repoRepository.findById(repoId)
                .orElseThrow(() -> new IllegalArgumentException("Repo not found: " + repoId));

        User user = userRepository.findById(repo.getUser().getId())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String content = gitHubContentClient.fetchFileContent(repo, user, file.getPath()).orElse("");

        log.info("[explain] file={} contentLen={} repo={} refresh={}",
                file.getPath(), content.length(), repo.getName(), refresh);

        String language = file.getLanguage() != null ? file.getLanguage() : inferLanguage(file.getPath());
        String roleSummary = file.getRoleSummary() != null ? file.getRoleSummary() : inferRole(file.getPath());
        String provider = resolveProvider(providerInput);

        if (content.isBlank()) {
            log.warn("[explain] GitHub returned empty content for path={} — using fallback diagram", file.getPath());
            DiagramPayload fb = fallback(file.getName());
            return toResponse(file, language, roleSummary, fb);
        }

        CanonicalRepo canonical = repo.getCanonicalRepo();
        String contentHash = ExplainCacheService.sha256(content);

        if (canonical != null && !refresh) {
            var cached = explainCacheService.find(canonical.getId(), file.getPath(), contentHash);
            if (cached.isPresent()) {
                var entry = cached.get();
                log.info("[explain] cache hit file={} hash={}", file.getPath(), contentHash.substring(0, 12));
                return toResponse(file, language, roleSummary, new DiagramPayload(
                        entry.getDiagramType(), entry.getMermaidCode(), entry.getSummary(),
                        explainCacheService.readConcepts(entry), entry.getProvider(), entry.getModel()));
            }
        }

        String relatedBlock = canonical != null ? relatedFilesBlock(canonical.getId(), file, content) : "";
        String condensed = CodeCondenser.condense(content);
        boolean skeletonized = false;
        if (condensed.length() > MAX_PROMPT_CODE_CHARS) {
            condensed = CodeCondenser.skeleton(condensed);
            skeletonized = true;
        }
        if (condensed.length() > MAX_PROMPT_CODE_CHARS) {
            condensed = condensed.substring(0, MAX_PROMPT_CODE_CHARS);
        }

        String userPrompt = buildUserPrompt(file.getPath(), language, roleSummary, condensed, relatedBlock, skeletonized);
        boolean complex = isComplexFile(roleSummary, file.getPath());
        DiagramPayload payload = diagramGenerationService.generate(
                provider, SYSTEM_PROMPT, userPrompt, complex, file.getPath());

        if (canonical != null) {
            explainCacheService.save(canonical.getId(), file.getPath(), contentHash,
                    payload.provider(), payload.model(),
                    payload.diagramType(), payload.mermaidCode(), payload.summary(), payload.concepts());
        }

        return toResponse(file, language, roleSummary, payload);
    }

    private FileExplainResponse toResponse(FileNode file, String language, String roleSummary, DiagramPayload payload) {
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

    /**
     * Resolves this file's imports against the repo's own files so the model
     * diagrams real collaborators (with their known roles) instead of guessing.
     */
    private String relatedFilesBlock(UUID canonicalRepoId, FileNode file, String rawContent) {
        Set<String> referenced = extractReferencedNames(rawContent);
        if (referenced.isEmpty()) {
            return "";
        }
        List<FileNode> candidates = fileNodeRepository.findByCanonicalRepoIdAndType(canonicalRepoId, "FILE");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (FileNode node : candidates) {
            if (node.getId().equals(file.getId())) continue;
            if (!referenced.contains(stripExtension(node.getName()))) continue;
            String role = node.getRoleSummary() != null && !node.getRoleSummary().isBlank()
                    ? node.getRoleSummary()
                    : inferRole(node.getPath());
            sb.append("- ").append(node.getPath()).append(" — ").append(role).append('\n');
            if (++count >= MAX_RELATED_FILES) break;
        }
        if (sb.isEmpty()) {
            return "";
        }
        return "Related files in this repository (real collaborators of this file):\n" + sb + "\n";
    }

    private Set<String> extractReferencedNames(String content) {
        Set<String> names = new LinkedHashSet<>();
        Matcher java = JAVA_IMPORT.matcher(content);
        while (java.find()) names.add(java.group(1));
        Matcher js = JS_IMPORT.matcher(content);
        while (js.find()) names.add(js.group(1));
        Matcher py = PY_IMPORT.matcher(content);
        while (py.find()) {
            if (py.group(1) != null) names.add(py.group(1));
            if (py.group(2) != null) names.add(py.group(2));
        }
        return names;
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private boolean isComplexFile(String role, String path) {
        String r = (role + " " + path).toLowerCase();
        if (r.contains("dto") || r.contains("entity") || r.contains("data model")
                || r.contains("/model") || r.contains("config")) {
            return false;
        }
        // Default to the stronger model — a failed call on a weak model costs
        // more than the price difference.
        return true;
    }

    private String buildUserPrompt(String path, String language, String role, String code,
                                   String relatedBlock, boolean skeletonized) {
        String note = skeletonized
                ? "NOTE: deeply nested bodies were elided (marked ...) — diagram the visible structure and control flow.\n\n"
                : "";
        return """
                Explain this file using a Mermaid diagram and return only JSON.

                Path: %s
                Language: %s
                Role: %s

                %s%sContent:
                ---
                %s
                ---
                """.formatted(path, language, role, relatedBlock, note, code);
    }

    private DiagramPayload fallback(String fileName) {
        String safe = fileName.replaceAll("[\"\\[\\]]", "");
        return new DiagramPayload(
                "flowchart",
                "flowchart TD\n    A[" + safe + "] --> B[Core Logic]\n    B --> C[Output]",
                "Diagram could not be generated — review the source file directly.",
                List.of("File: " + fileName),
                null,
                null
        );
    }

    private String resolveProvider(String input) {
        return (input == null || input.isBlank()) ? "NVIDIA_DEV" : input.trim().toUpperCase();
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
}
