package com.repomind.backend.service.explain;

import com.repomind.backend.domain.repo.CanonicalRepo;
import com.repomind.backend.domain.repo.FileNode;
import com.repomind.backend.domain.repo.FileNodeRepository;
import com.repomind.backend.domain.repo.Repo;
import com.repomind.backend.domain.repo.RepoRepository;
import com.repomind.backend.service.explain.DiagramGenerationService.DiagramPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One system-design diagram per repository, generated from the file tree and
 * per-file role summaries (no file contents — one cheap LLM call), cached
 * under the reserved path {@code __repo_overview__} keyed by a tree
 * fingerprint so it regenerates only when the repo structure changes.
 */
@Service
@Transactional(readOnly = true)
public class RepoOverviewService {

    private static final Logger log = LoggerFactory.getLogger(RepoOverviewService.class);

    static final String OVERVIEW_PATH = "__repo_overview__";
    private static final int MAX_TREE_LINES = 400;

    private static final String SYSTEM_PROMPT = """
            You are a senior software architect. From a repository's file tree and per-file roles, produce ONE Mermaid flowchart showing the SYSTEM DESIGN of the whole project: its modules, layers, entry points, data stores, and how data flows between them.

            CRITICAL: Respond ONLY with a single valid JSON object. No markdown fences, no explanation, no text outside the JSON.

            Required JSON format:
            {"diagramType":"flowchart","mermaidCode":"<mermaid code with \\\\n for line breaks>","summary":"<two sentences describing the system>","concepts":["<c1>","<c2>","<c3>","<c4>","<c5>"]}

            Content policy:
            - Use subgraph blocks for each real module or layer (derive them from the directory structure, e.g. frontend, backend api, services, persistence).
            - Show the main runtime flow between layers with labeled arrows (request handling, message queues, DB reads/writes).
            - Infer infrastructure from file names (docker-compose, Kafka, Redis, migrations imply Postgres, etc.) and show it as :::db or :::external nodes.
            - 20 to 40 nodes. Represent groups of similar files as one node (e.g. one node for all entities), never one node per file.
            - Tag nodes: :::entry (user-facing entry points), :::db (data stores), :::external (third-party APIs), :::helper (supporting utilities). Do NOT emit classDef lines — the renderer defines these classes.

            Mermaid syntax rules (STRICT — invalid syntax causes rendering failure):
            - flowchart TD: nodes A[Label], decisions A{Condition}, arrows -->, labeled arrows -->|label|
            - subgraph Name ... end, each statement on ONE line
            - Node / label / subgraph text must NEVER contain: " (double-quote), [ ] { } ( ) : (colon) — plain words and spaces only. The :::styleTag suffix is the ONLY allowed colon usage.
            - Newlines in JSON string: use \\n (literal backslash-n, never a real newline character)
            """;

    private final RepoRepository repoRepository;
    private final FileNodeRepository fileNodeRepository;
    private final DiagramGenerationService diagramGenerationService;
    private final ExplainCacheService explainCacheService;

    public RepoOverviewService(
            RepoRepository repoRepository,
            FileNodeRepository fileNodeRepository,
            DiagramGenerationService diagramGenerationService,
            ExplainCacheService explainCacheService) {
        this.repoRepository = repoRepository;
        this.fileNodeRepository = fileNodeRepository;
        this.diagramGenerationService = diagramGenerationService;
        this.explainCacheService = explainCacheService;
    }

    public FileExplainResponse overview(UUID repoId, String providerInput, boolean refresh) {
        Repo repo = repoRepository.findById(repoId)
                .orElseThrow(() -> new IllegalArgumentException("Repo not found: " + repoId));
        CanonicalRepo canonical = repo.getCanonicalRepo();
        if (canonical == null) {
            throw new IllegalArgumentException("Repository is still being ingested — try again shortly.");
        }

        List<FileNode> files = fileNodeRepository.findByCanonicalRepoIdOrderByPathAsc(canonical.getId())
                .stream()
                .filter(n -> "FILE".equals(n.getType()))
                .toList();
        if (files.isEmpty()) {
            throw new IllegalArgumentException("Repository has no ingested files yet.");
        }

        // Fingerprint of the tree: overview regenerates only when structure changes.
        String fingerprint = ExplainCacheService.sha256(
                files.stream().map(FileNode::getPath).collect(Collectors.joining("\n")));

        if (!refresh) {
            var cached = explainCacheService.find(canonical.getId(), OVERVIEW_PATH, fingerprint);
            if (cached.isPresent()) {
                var entry = cached.get();
                log.info("[overview] cache hit repo={} fingerprint={}", repo.getName(), fingerprint.substring(0, 12));
                return toResponse(repo, new DiagramPayload(
                        entry.getDiagramType(), entry.getMermaidCode(), entry.getSummary(),
                        explainCacheService.readConcepts(entry), entry.getProvider(), entry.getModel()));
            }
        }

        String provider = (providerInput == null || providerInput.isBlank())
                ? "GROQ" : providerInput.trim().toUpperCase();
        String userPrompt = buildUserPrompt(repo, files);
        DiagramPayload payload = diagramGenerationService.generate(
                provider, SYSTEM_PROMPT, userPrompt, true, "overview:" + repo.getName());

        explainCacheService.save(canonical.getId(), OVERVIEW_PATH, fingerprint,
                payload.provider(), payload.model(),
                payload.diagramType(), payload.mermaidCode(), payload.summary(), payload.concepts());

        return toResponse(repo, payload);
    }

    private String buildUserPrompt(Repo repo, List<FileNode> files) {
        StringBuilder tree = new StringBuilder();
        int lines = 0;
        for (FileNode node : files) {
            tree.append(node.getPath());
            if (node.getRoleSummary() != null && !node.getRoleSummary().isBlank()) {
                tree.append(" — ").append(node.getRoleSummary());
            }
            tree.append('\n');
            if (++lines >= MAX_TREE_LINES) {
                tree.append("... (").append(files.size() - lines).append(" more files)\n");
                break;
            }
        }
        return """
                Draw the system design of this repository as one Mermaid flowchart and return only JSON.

                Repository: %s/%s
                Files (%d total):
                ---
                %s---
                """.formatted(repo.getOwner(), repo.getName(), files.size(), tree);
    }

    private FileExplainResponse toResponse(Repo repo, DiagramPayload payload) {
        return new FileExplainResponse(
                "overview:" + repo.getId(),
                repo.getOwner() + "/" + repo.getName(),
                repo.getName() + " — system overview",
                "",
                0L,
                "Whole-repository architecture",
                payload.diagramType(),
                payload.mermaidCode(),
                payload.summary(),
                payload.concepts()
        );
    }
}
