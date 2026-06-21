package com.repomind.backend.service.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.backend.domain.ai.AiCallLog;
import com.repomind.backend.domain.ai.AiCallLogRepository;
import com.repomind.backend.domain.analysis.Analysis;
import com.repomind.backend.domain.analysis.AnalysisRepository;
import com.repomind.backend.domain.analysis.AnalysisStage;
import com.repomind.backend.domain.analysis.AnalysisStageRepository;
import com.repomind.backend.domain.repo.FileNode;
import com.repomind.backend.domain.repo.FileNodeRepository;
import com.repomind.backend.domain.repo.Repo;
import com.repomind.backend.domain.repo.RepoRepository;
import com.repomind.backend.domain.user.User;
import com.repomind.backend.domain.user.UserRepository;
import com.repomind.backend.service.ai.AiProviderRouter;
import com.repomind.backend.service.ai.dto.AiEmbeddingRequest;
import com.repomind.backend.service.ai.dto.AiEmbeddingResponse;
import com.repomind.backend.service.ai.dto.AiGenerationRequest;
import com.repomind.backend.service.ai.dto.AiGenerationResponse;
import com.repomind.backend.service.retrieval.RetrievalService;
import com.repomind.backend.service.analysis.dto.AnalysisResponse;
import com.repomind.backend.service.analysis.dto.AnalysisStageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnalysisPipelineService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPipelineService.class);

    private static final List<StageDefinition> STAGES = List.of(
            new StageDefinition(1, "OVERVIEW"),
            new StageDefinition(2, "ARCHITECTURE"),
            new StageDefinition(3, "MODULE_MAPPING"),
            new StageDefinition(4, "DATA_FLOW"),
            new StageDefinition(5, "BUG_DETECTION"),
            new StageDefinition(6, "FILE_ANNOTATION"),
            new StageDefinition(7, "EMBEDDING")
    );
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "kt", "groovy", "js", "jsx", "ts", "tsx", "py", "go", "rs",
            "c", "cpp", "h", "hpp", "cs", "php", "rb", "swift", "scala",
            "sql", "xml", "json", "yml", "yaml", "toml", "ini", "properties", "md"
    );
    private static final long MAX_ANNOTATION_FILE_SIZE_BYTES = 250_000L;
    private static final long MAX_EMBED_FILE_SIZE_BYTES = 500_000L;
    private static final String GENERIC_ROLE = "Contributes to repository functionality and module behavior.";
    private static final long MIN_REMAINING_MS_FOR_DEEP_LLM = 30_000L;
    private static final long MIN_REMAINING_MS_FOR_QUALITY_SYNTHESIS = 20_000L;
    private static final Set<String> ENTRYPOINT_HINTS = Set.of(
            "main.cpp", "main.c", "server.cpp", "app.cpp", "application.java", "main.py", "index.js"
    );
    private static final Set<String> INFRA_PATH_HINTS = Set.of(
            ".vscode/", ".idea/", "docker/", "/docker/", "docker-compose", ".github/", "terraform/", "helm/", "k8s/", "node_modules/"
    );
    private static final Set<String> DOC_PATH_HINTS = Set.of(
            "readme", "changelog", "contributing", "/docs/", "architecture.md", "design.md"
    );
    private static final Set<String> TEST_PATH_HINTS = Set.of(
            "/test/", "/tests/", "_test.", "test_", "spec.", "/fixtures/"
    );

    private final AnalysisRepository analysisRepository;
    private final AnalysisStageRepository analysisStageRepository;
    private final RepoRepository repoRepository;
    private final UserRepository userRepository;
    private final FileNodeRepository fileNodeRepository;
    private final AiProviderRouter aiProviderRouter;
    private final AiCallLogRepository aiCallLogRepository;
    private final ObjectMapper objectMapper;
    private final RetrievalService retrievalService;
    private final boolean annotationAiEnrichmentEnabled;
    private final int annotationAiMaxFiles;
    private final int annotationAiMaxBatches;
    private final boolean stageAiSummaryEnabled;
    private final boolean deepPassEnabled;
    private final long maxDurationMs;
    private final String defaultQualityProfile;

    public AnalysisPipelineService(
            AnalysisRepository analysisRepository,
            AnalysisStageRepository analysisStageRepository,
            RepoRepository repoRepository,
            UserRepository userRepository,
            FileNodeRepository fileNodeRepository,
            AiProviderRouter aiProviderRouter,
            AiCallLogRepository aiCallLogRepository,
            ObjectMapper objectMapper,
            RetrievalService retrievalService,
            @Value("${app.analysis.annotation.ai-enrichment:false}") boolean annotationAiEnrichmentEnabled,
            @Value("${app.analysis.annotation.ai-max-files:60}") int annotationAiMaxFiles,
            @Value("${app.analysis.annotation.ai-max-batches:2}") int annotationAiMaxBatches,
            @Value("${app.analysis.stage-ai-summary-enabled:false}") boolean stageAiSummaryEnabled,
            @Value("${app.analysis.deep-pass.enabled:true}") boolean deepPassEnabled,
            @Value("${app.analysis.max-duration-ms:120000}") long maxDurationMs,
            @Value("${app.analysis.quality-profile:BALANCED}") String defaultQualityProfile
    ) {
        this.analysisRepository = analysisRepository;
        this.analysisStageRepository = analysisStageRepository;
        this.repoRepository = repoRepository;
        this.userRepository = userRepository;
        this.fileNodeRepository = fileNodeRepository;
        this.aiProviderRouter = aiProviderRouter;
        this.aiCallLogRepository = aiCallLogRepository;
        this.objectMapper = objectMapper;
        this.retrievalService = retrievalService;
        this.annotationAiEnrichmentEnabled = annotationAiEnrichmentEnabled;
        this.annotationAiMaxFiles = annotationAiMaxFiles;
        this.annotationAiMaxBatches = annotationAiMaxBatches;
        this.stageAiSummaryEnabled = stageAiSummaryEnabled;
        this.deepPassEnabled = deepPassEnabled;
        this.maxDurationMs = Math.max(45_000L, maxDurationMs);
        this.defaultQualityProfile = defaultQualityProfile == null ? "BALANCED" : defaultQualityProfile;
    }

    @Transactional
    public AnalysisResponse runPipeline(UUID repoId, UUID userId, String aiProviderInput) {
        Repo repo = repoRepository.findById(repoId)
                .orElseThrow(() -> new IllegalArgumentException("Repository not found: " + repoId));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        String aiProvider = normalizeProvider(aiProviderInput);

        Analysis analysis = Analysis.builder()
                .repo(repo)
                .user(user)
                .aiProvider(aiProvider)
                .status("RUNNING")
                .currentStage(0)
                .tokensUsed(0)
                .build();
        analysis = analysisRepository.save(analysis);

        List<AnalysisStage> stages = createStages(analysis);
        List<FileNode> repoNodes = fileNodeRepository.findByRepoIdOrderByPathAsc(repoId);
        List<FileNode> repoFiles = repoNodes.stream()
                .filter(node -> "FILE".equalsIgnoreCase(node.getType()))
                .toList();
        List<FileNode> analyzableFiles = repoFiles.stream()
                .filter(this::isAnalyzableTextFile)
                .toList();
        QualityProfile qualityProfile = resolveQualityProfile(repoFiles.size());
        long startedAtMs = System.currentTimeMillis();
        PipelineContext ctx = new PipelineContext(startedAtMs + maxDurationMs, qualityProfile);

        Map<String, Object> pipelineResult = new LinkedHashMap<>();
        int totalTokens = 0;
        pipelineResult.put("PIPELINE_META", Map.of(
                "qualityProfile", qualityProfile.name(),
                "maxDurationMs", maxDurationMs
        ));

        try {
            for (AnalysisStage stage : stages) {
                long stageStartMs = System.currentTimeMillis();
                stage.setStatus("RUNNING");
                stage.setStartedAt(Instant.now());
                analysisStageRepository.save(stage);

                analysis.setCurrentStage(stage.getStageNumber());
                analysisRepository.save(analysis);

                StageExecutionResult stageExecutionResult = runStage(stage, analysis, repo, repoNodes, repoFiles, analyzableFiles, pipelineResult, ctx);
                Map<String, Object> stageOutput = stageExecutionResult.output();
                stageOutput.put("durationMs", Math.max(0L, System.currentTimeMillis() - stageStartMs));
                stageOutput.put("remainingBudgetMs", remainingMs(ctx));
                int stageTokens = stageExecutionResult.tokensUsed() > 0
                        ? stageExecutionResult.tokensUsed()
                        : estimateTokens(stageOutput);
                totalTokens += stageTokens;

                stage.setResult(toJson(stageOutput));
                stage.setTokensUsed(stageTokens);
                stage.setStatus("COMPLETED");
                stage.setCompletedAt(Instant.now());
                analysisStageRepository.save(stage);

                pipelineResult.put(stage.getStageName(), stageOutput);
            }

            if (deepPassEnabled) {
                pipelineResult.put("DEEP_PASS", buildDeepPass(analysis, ctx));
            }

            if (remainingMs(ctx) > MIN_REMAINING_MS_FOR_QUALITY_SYNTHESIS) {
                pipelineResult.put("QUALITY_SYNTHESIS", buildQualitySynthesis(analysis, pipelineResult));
            }

            analysis.setStatus("COMPLETED");
            analysis.setTokensUsed(totalTokens);
            analysis.setResult(toJson(pipelineResult));
            analysis.setCompletedAt(Instant.now());
            analysis.setErrorMsg(null);
            analysisRepository.save(analysis);
        } catch (Exception ex) {
            log.error("Analysis pipeline failed for analysisId={}", analysis.getId(), ex);
            analysis.setStatus("FAILED");
            analysis.setErrorMsg(ex.getMessage());
            analysis.setCompletedAt(Instant.now());
            analysisRepository.save(analysis);
            throw ex;
        }

        return AnalysisResponse.from(analysis);
    }

    public AnalysisResponse getAnalysis(UUID analysisId) {
        Analysis analysis = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new IllegalArgumentException("Analysis not found: " + analysisId));
        return AnalysisResponse.from(analysis);
    }

    public List<AnalysisStageResponse> getStages(UUID analysisId) {
        return analysisStageRepository.findByAnalysisIdOrderByStageNumberAsc(analysisId)
                .stream()
                .map(AnalysisStageResponse::from)
                .toList();
    }

    private List<AnalysisStage> createStages(Analysis analysis) {
        List<AnalysisStage> stages = STAGES.stream()
                .map(def -> AnalysisStage.builder()
                        .analysis(analysis)
                        .stageNumber(def.number())
                        .stageName(def.name())
                        .status("PENDING")
                        .tokensUsed(0)
                        .build())
                .toList();
        return analysisStageRepository.saveAll(stages);
    }

    private StageExecutionResult runStage(
            AnalysisStage stage,
            Analysis analysis,
            Repo repo,
            List<FileNode> repoNodes,
            List<FileNode> repoFiles,
            List<FileNode> analyzableFiles,
            Map<String, Object> previousStages,
            PipelineContext ctx
    ) {
        return switch (stage.getStageName()) {
            case "OVERVIEW" -> buildOverview(analysis, repo, repoNodes, repoFiles);
            case "ARCHITECTURE" -> buildArchitecture(analysis, repoNodes);
            case "MODULE_MAPPING" -> buildModuleMap(analysis, analyzableFiles, ctx);
            case "DATA_FLOW" -> buildDataFlow(analysis, analyzableFiles, ctx);
            case "BUG_DETECTION" -> buildRiskScan(analysis, analyzableFiles, ctx);
            case "FILE_ANNOTATION" -> annotateFiles(analysis, analyzableFiles, ctx);
            case "EMBEDDING" -> buildEmbeddingStage(analysis, analyzableFiles, previousStages, ctx);
            default -> throw new IllegalStateException("Unknown stage: " + stage.getStageName());
        };
    }

    private StageExecutionResult buildOverview(Analysis analysis, Repo repo, List<FileNode> nodes, List<FileNode> files) {
        Map<String, Long> extensionCounts = files.stream()
                .collect(Collectors.groupingBy(this::getExtension, Collectors.counting()));

        List<String> techStack = extensionCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(6)
                .map(entry -> mapExtensionToTech(entry.getKey()))
                .distinct()
                .toList();

        String entryPoint = files.stream()
                .map(FileNode::getPath)
                .sorted(Comparator.comparingInt(this::entrypointPriority).reversed())
                .filter(path -> entrypointPriority(path) > 0)
                .findFirst()
                .orElse(files.isEmpty() ? "" : files.get(0).getPath());

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("repoName", repo.getName());
        overview.put("owner", repo.getOwner());
        overview.put("defaultBranch", repo.getDefaultBranch());
        overview.put("fileCount", files.size());
        overview.put("directoryCount", nodes.size() - files.size());
        overview.put("techStack", techStack);
        overview.put("entryPoint", entryPoint);
        String summary = stageAiSummaryEnabled
                ? runStageSummary(
                analysis,
                "OVERVIEW",
                """
                        Create a concise summary in 1-2 sentences about repository purpose and stack.
                        Return plain text only.
                        """,
                "Repo context: " + toJson(overview),
                400
        )
                : buildOverviewHeuristicSummary(repo, files.size(), nodes.size() - files.size(), techStack, entryPoint);
        overview.put("summary", summary);
        return new StageExecutionResult(overview, 0);
    }

    private StageExecutionResult buildArchitecture(Analysis analysis, List<FileNode> nodes) {
        Set<String> topLevel = nodes.stream()
                .map(FileNode::getPath)
                .filter(path -> path.contains("/"))
                .map(path -> path.substring(0, path.indexOf('/')))
                .collect(Collectors.toCollection(TreeSet::new));

        Map<String, Object> architecture = new LinkedHashMap<>();
        architecture.put("style", "Monolith");
        architecture.put("topLevelModules", topLevel);
        String summary = stageAiSummaryEnabled
                ? runStageSummary(
                analysis,
                "ARCHITECTURE",
                """
                        Infer architecture style and explain module boundaries in max 2 sentences.
                        Return plain text only.
                        """,
                "Top-level modules: " + topLevel,
                400
        )
                : buildArchitectureHeuristicSummary(topLevel);
        architecture.put("summary", summary);
        return new StageExecutionResult(architecture, 0);
    }

    private StageExecutionResult buildModuleMap(Analysis analysis, List<FileNode> files, PipelineContext ctx) {
        int moduleLimit = switch (ctx.qualityProfile()) {
            case DEEP -> 45;
            case BALANCED -> 35;
            case FAST -> 25;
        };
        List<FileNode> moduleCandidates = files.stream()
                .filter(this::isModuleCandidate)
                .sorted(Comparator.comparingInt((FileNode file) -> modulePathPriority(file.getPath())).reversed())
                .toList();
        if (moduleCandidates.isEmpty()) {
            moduleCandidates = files.stream()
                    .sorted(Comparator.comparingInt((FileNode file) -> modulePathPriority(file.getPath())).reversed())
                    .toList();
        }

        List<Map<String, Object>> modules = moduleCandidates.stream()
                .limit(moduleLimit)
                .map(file -> {
                    Map<String, Object> module = new LinkedHashMap<>();
                    module.put("path", file.getPath());
                    module.put("language", Optional.ofNullable(file.getLanguage()).orElseGet(() -> inferLanguage(file.getPath())));
                    module.put("role", inferRole(file.getPath()));
                    return module;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("moduleCount", modules.size());
        result.put("modules", modules);
        result.put("moduleSelectionLimit", moduleLimit);
        result.put("moduleCandidateCount", moduleCandidates.size());
        String stageSummary = stageAiSummaryEnabled
                ? runStageSummary(
                analysis,
                "MODULE_MAPPING",
                """
                        Review module list and provide one short paragraph describing responsibility split.
                        Return plain text only.
                        """,
                toJson(modules),
                500
        )
                : buildModuleHeuristicSummary(modules);
        result.put("summary", stageSummary);
        return new StageExecutionResult(result, 0);
    }

    private StageExecutionResult buildDataFlow(Analysis analysis, List<FileNode> files, PipelineContext ctx) {
        int perLayerLimit = switch (ctx.qualityProfile()) {
            case DEEP -> 16;
            case BALANCED -> 12;
            case FAST -> 9;
        };

        List<FileNode> runtimeFlowCandidates = files.stream()
                .filter(this::isRuntimeFlowCandidate)
                .toList();
        List<FileNode> dataAccessCandidates = files.stream()
                .filter(this::isDataAccessCandidate)
                .toList();

        List<String> controllers = filterByAnyKeyword(
                runtimeFlowCandidates,
                List.of("controller", "router", "route", "endpoint", "handler", "server", "main"),
                perLayerLimit,
                true
        ).stream().filter(this::isControllerLikePath).toList();
        List<String> services = filterByAnyKeyword(
                runtimeFlowCandidates,
                List.of("service", "manager", "processor", "orchestrator", "worker", "thread_pool", "usecase"),
                perLayerLimit,
                true
        ).stream().filter(this::isServiceLikePath).toList();
        List<String> repositories = filterByAnyKeyword(
                dataAccessCandidates,
                List.of("repository", "dao", "store", "client", "db", "database", "mysql", "postgres", "redis", "qdrant"),
                perLayerLimit,
                false
        ).stream().filter(this::isRepositoryLikePath).toList();

        if (controllers.isEmpty()) {
            controllers = highestPriorityPaths(runtimeFlowCandidates, perLayerLimit);
        }
        if (controllers.isEmpty()) {
            controllers = highestPriorityPaths(files.stream().filter(this::isModuleCandidate).toList(), perLayerLimit);
        }
        if (repositories.isEmpty()) {
            repositories = filterByAnyKeyword(
                    files.stream().filter(this::isModuleCandidate).toList(),
                    List.of("sql", "mysql", "postgres", "db", "store", "repository"),
                    perLayerLimit,
                    true
            );
        }

        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("controllers", controllers);
        flow.put("services", services);
        flow.put("repositories", repositories);
        flow.put("perLayerLimit", perLayerLimit);
        flow.put("runtimeCandidateCount", runtimeFlowCandidates.size());
        flow.put("dataCandidateCount", dataAccessCandidates.size());
        String summary = stageAiSummaryEnabled
                ? runStageSummary(
                analysis,
                "DATA_FLOW",
                """
                        Based on entrypoint/service/data-access file lists, describe probable request lifecycle.
                        If framework-specific controller/service layers are absent, explain equivalent runtime flow for this stack.
                        Keep to 2-3 sentences.
                        """,
                toJson(flow),
                520
        )
                : buildDataFlowHeuristicSummary(controllers, services, repositories);
        flow.put("summary", summary);
        return new StageExecutionResult(flow, 0);
    }

    private StageExecutionResult buildRiskScan(Analysis analysis, List<FileNode> files, PipelineContext ctx) {
        int riskLimit = switch (ctx.qualityProfile()) {
            case DEEP -> 12;
            case BALANCED -> 8;
            case FAST -> 6;
        };
        List<FileNode> riskCandidates = files.stream()
                .filter(this::isRiskCandidate)
                .toList();
        if (riskCandidates.isEmpty()) {
            riskCandidates = files;
        }

        List<Map<String, Object>> risks = riskCandidates.stream()
                .map(this::buildRiskEntry)
                .sorted(Comparator
                        .comparingInt((Map<String, Object> risk) -> (Integer) risk.getOrDefault("riskScore", 0))
                        .reversed()
                        .thenComparing(Comparator.comparingLong((Map<String, Object> risk) ->
                                ((Number) risk.getOrDefault("sizeBytes", 0L)).longValue()).reversed()))
                .filter(risk -> ((Integer) risk.getOrDefault("riskScore", 0)) >= 20)
                .limit(riskLimit)
                .toList();

        if (risks.isEmpty()) {
            risks = files.stream()
                    .sorted(Comparator.comparingInt((FileNode file) -> modulePathPriority(file.getPath())).reversed())
                    .limit(Math.max(3, riskLimit))
                    .map(this::buildRiskEntry)
                    .toList();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("riskAreas", risks);
        result.put("riskSelectionLimit", riskLimit);
        result.put("riskCandidateCount", riskCandidates.size());
        result.put("riskMethod", "heuristic-signals-v2");
        String summary = stageAiSummaryEnabled
                ? runStageSummary(
                analysis,
                "BUG_DETECTION",
                """
                        Provide top 3 technical risks from the provided risk areas.
                        Prioritize concurrency, request handling, data integrity, and error handling risk.
                        Keep concise. Return plain text only.
                        """,
                toJson(risks),
                500
        )
                : buildRiskHeuristicSummary(risks);
        result.put("summary", summary);
        return new StageExecutionResult(result, 0);
    }

    private StageExecutionResult annotateFiles(Analysis analysis, List<FileNode> files, PipelineContext ctx) {
        List<FileNode> candidates = files.stream()
                .filter(file -> Optional.ofNullable(file.getSizeBytes()).orElse(0L) <= MAX_ANNOTATION_FILE_SIZE_BYTES)
                .toList();

        int updated = 0;
        int tokensUsed = 0;
        int unchanged = 0;

        Map<String, String> heuristicSummaries = candidates.stream()
                .collect(Collectors.toMap(FileNode::getPath, file -> inferRole(file.getPath())));

        for (FileNode file : candidates) {
            String summary = heuristicSummaries.get(file.getPath());
            if (Objects.equals(normalizeForCompare(file.getRoleSummary()), normalizeForCompare(summary))) {
                unchanged++;
                continue;
            }
            fileNodeRepository.updateRoleSummary(file.getId(), summary);
            updated++;
        }

        boolean canUseAiEnrichment = annotationAiEnrichmentEnabled
                && remainingMs(ctx) > MIN_REMAINING_MS_FOR_DEEP_LLM;
        if (canUseAiEnrichment) {
            List<FileNode> aiTargets = candidates.stream()
                    .filter(file -> GENERIC_ROLE.equals(heuristicSummaries.get(file.getPath())))
                    .sorted(Comparator.comparingInt((FileNode file) -> dataFlowPathPriority(file.getPath())).reversed())
                    .limit(annotationAiMaxFiles)
                    .toList();

            int completedBatches = 0;
            for (int i = 0; i < aiTargets.size(); i += 20) {
                if (completedBatches >= annotationAiMaxBatches) {
                    break;
                }
                List<FileNode> batch = aiTargets.subList(i, Math.min(i + 20, aiTargets.size()));
                Map<String, String> summaries = generateFileSummaries(analysis, batch);
                for (FileNode file : batch) {
                    String summary = summaries.getOrDefault(file.getPath(), heuristicSummaries.get(file.getPath()));
                    if (summary == null || summary.isBlank() || GENERIC_ROLE.equalsIgnoreCase(summary.trim())) {
                        summary = heuristicSummaries.get(file.getPath());
                    }
                    if (!Objects.equals(normalizeForCompare(file.getRoleSummary()), normalizeForCompare(summary))) {
                        fileNodeRepository.updateRoleSummary(file.getId(), summary);
                    }
                }
                completedBatches++;
                tokensUsed += summaries.size() * 18;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("annotatedFiles", updated);
        result.put("unchangedFiles", unchanged);
        result.put("skippedLargeFiles", files.size() - candidates.size());
        result.put("maxFileSizeBytes", MAX_ANNOTATION_FILE_SIZE_BYTES);
        result.put("aiEnrichmentEnabled", canUseAiEnrichment);
        result.put("summary", "Role summaries generated and persisted.");
        return new StageExecutionResult(result, tokensUsed);
    }

    private StageExecutionResult buildEmbeddingStage(
            Analysis analysis,
            List<FileNode> files,
            Map<String, Object> previousStages,
            PipelineContext ctx
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dependsOn", new ArrayList<>(previousStages.keySet()));
        try {
            int fileCap = switch (ctx.qualityProfile()) {
                case DEEP -> 260;
                case BALANCED -> 190;
                case FAST -> 120;
            };
            int chunkCap = switch (ctx.qualityProfile()) {
                case DEEP -> 900;
                case BALANCED -> 650;
                case FAST -> 420;
            };
            long remainingBudgetMs = remainingMs(ctx);
            if (remainingBudgetMs < 65_000) {
                fileCap = Math.min(fileCap, 120);
                chunkCap = Math.min(chunkCap, 420);
            }
            if (remainingBudgetMs < 35_000) {
                fileCap = Math.min(fileCap, 80);
                chunkCap = Math.min(chunkCap, 260);
            }
            RetrievalService.IndexResult indexResult = retrievalService.indexRepo(
                    analysis.getRepo().getId(),
                    analysis.getUser().getId(),
                    analysis.getAiProvider(),
                    fileCap,
                    chunkCap,
                    ctx.deadlineEpochMs() - 2_000L
            );
            result.put("embeddedFiles", indexResult.embeddedFiles());
            result.put("totalChunks", indexResult.embeddedChunks());
            result.put("skippedFiles", indexResult.skippedFiles());
            result.put("chunkSizeChars", indexResult.chunkSizeChars());
            result.put("overlapChars", indexResult.chunkOverlapChars());
            result.put("maxFilesUsed", indexResult.maxFilesUsed());
            result.put("maxChunksUsed", indexResult.maxChunksUsed());
            result.put("status", "COMPLETED");
            result.put("summary", "Embeddings generated and upserted to Qdrant with repo-scoped payload metadata.");
            return new StageExecutionResult(result, indexResult.tokensUsed());
        } catch (Exception ex) {
            log.warn("Embedding stage failed for analysisId={}. Proceeding with degraded output.", analysis.getId(), ex);
            result.put("embeddedFiles", 0);
            result.put("totalChunks", 0);
            result.put("skippedFiles", files.size());
            result.put("status", "DEGRADED");
            result.put("error", ex.getMessage());
            result.put("summary", "Embedding stage failed; analysis completed without vector indexing.");
            return new StageExecutionResult(result, 0);
        }
    }

    private Map<String, Object> buildDeepPass(Analysis analysis, PipelineContext ctx) {
        Map<String, Object> deep = new LinkedHashMap<>();
        try {
            RetrievalService.SearchResult flowSearch = retrievalService.search(
                    analysis.getRepo().getId(),
                    "Trace the runtime request lifecycle from server entrypoint through thread pool and queue to response handling.",
                    6,
                    analysis.getAiProvider()
            );
            RetrievalService.SearchResult architectureSearch = retrievalService.search(
                    analysis.getRepo().getId(),
                    "Explain core architectural components, responsibilities, and coupling risks.",
                    6,
                    analysis.getAiProvider()
            );

            DeepSynthesisOutput synthesis = buildDeepSynthesis(analysis, flowSearch, architectureSearch, ctx);

            deep.put("status", "COMPLETED");
            deep.put("flowNarrative", synthesis.flowNarrative());
            deep.put("architectureNarrative", synthesis.architectureNarrative());
            deep.put("keyRisks", synthesis.keyRisks());
            deep.put("improvementActions", synthesis.improvementActions());
            deep.put("confidence", synthesis.confidence());
            deep.put("flowContextPaths", flowSearch.chunks().stream().map(RetrievalService.SearchChunk::path).distinct().toList());
            deep.put("architectureContextPaths", architectureSearch.chunks().stream().map(RetrievalService.SearchChunk::path).distinct().toList());
        } catch (Exception ex) {
            deep.put("status", "DEGRADED");
            deep.put("error", ex.getMessage());
            deep.put("flowNarrative", "- [evidence: unavailable] Deep synthesis unavailable due to provider/runtime failure.");
            deep.put("architectureNarrative", "- [evidence: unavailable] Deep synthesis unavailable due to provider/runtime failure.");
            deep.put("keyRisks", List.of("Provider/runtime instability can hide architecture and flow risk signals."));
            deep.put("improvementActions", List.of("Re-run deep synthesis with stable provider SLA and retrieval context."));
            deep.put("confidence", "LOW");
        }
        return deep;
    }

    private DeepSynthesisOutput buildDeepSynthesis(
            Analysis analysis,
            RetrievalService.SearchResult flowSearch,
            RetrievalService.SearchResult architectureSearch,
            PipelineContext ctx
    ) {
        List<RetrievalService.SearchChunk> merged = new ArrayList<>();
        merged.addAll(flowSearch.chunks());
        merged.addAll(architectureSearch.chunks());
        List<RetrievalService.SearchChunk> topContext = merged.stream()
                .collect(Collectors.toMap(
                        chunk -> chunk.path() + "#" + chunk.chunkIndex(),
                        chunk -> chunk,
                        (left, right) -> left.score() >= right.score() ? left : right
                ))
                .values()
                .stream()
                .sorted(Comparator.comparingDouble(RetrievalService.SearchChunk::score).reversed())
                .limit(ctx.qualityProfile() == QualityProfile.DEEP ? 10 : 8)
                .toList();

        String contextText = topContext.stream()
                .map(chunk -> "Path: " + chunk.path()
                        + " | score=" + String.format(Locale.ROOT, "%.4f", chunk.score())
                        + "\nSnippet:\n" + trimForPrompt(chunk.text(), 950))
                .collect(Collectors.joining("\n\n---\n\n"));

        if (contextText.isBlank()) {
            return heuristicDeepFallback(flowSearch, architectureSearch);
        }

        if (remainingMs(ctx) < MIN_REMAINING_MS_FOR_DEEP_LLM) {
            return heuristicDeepFallback(flowSearch, architectureSearch);
        }

        List<String> flowEvidencePaths = flowSearch.chunks().stream()
                .map(RetrievalService.SearchChunk::path)
                .distinct()
                .limit(8)
                .toList();
        List<String> architectureEvidencePaths = architectureSearch.chunks().stream()
                .map(RetrievalService.SearchChunk::path)
                .distinct()
                .limit(8)
                .toList();

        String instructions = """
                You are performing enterprise-grade codebase forensics.
                Return strict JSON with keys:
                - flowNarrative: array of 4-7 technical bullet strings
                - architectureNarrative: array of 4-7 technical bullet strings
                - keyRisks: array of 3-5 concrete risks grounded in observed files
                - improvementActions: array of 3-5 prioritized actions with rationale
                - confidence: one of HIGH, MEDIUM, LOW
                Rules:
                - Ground every statement in visible evidence from context snippets.
                - Each bullet must start with: [evidence: path1, path2] ...
                - If uncertain, state uncertainty directly.
                - No markdown fences.
                """;

        String userPrompt = """
                Repository: %s/%s
                Runtime profile: %s
                Context:
                %s
                """.formatted(
                analysis.getRepo().getOwner(),
                analysis.getRepo().getName(),
                ctx.qualityProfile().name(),
                contextText
        );

        try {
            AiGenerationResponse response = executeGenerationCall(analysis, "DEEP_PASS", instructions, userPrompt, 900);
            String cleaned = stripMarkdownFences(response.text());
            Map<String, Object> root = objectMapper.readValue(cleaned, new TypeReference<>() {});
            List<String> flowBullets = enforceEvidenceBullets(
                    listOfStrings(root.get("flowNarrative")),
                    flowEvidencePaths,
                    "Runtime flow path is inferred from retrieved server/thread handling files."
            );
            List<String> architectureBullets = enforceEvidenceBullets(
                    listOfStrings(root.get("architectureNarrative")),
                    architectureEvidencePaths,
                    "Architecture boundaries are inferred from retrieved runtime/data components."
            );
            return new DeepSynthesisOutput(
                    joinBullets(flowBullets, "Deep synthesis returned empty flow narrative."),
                    joinBullets(architectureBullets, "Deep synthesis returned empty architecture narrative."),
                    ensureNonEmptyList(listOfStrings(root.get("keyRisks")),
                            List.of("Insufficient deterministic evidence to rank high-confidence risks.")),
                    ensureNonEmptyList(listOfStrings(root.get("improvementActions")),
                            List.of("Increase targeted retrieval depth for request/data-flow critical paths.")),
                    normalizeConfidence(root.get("confidence"))
            );
        } catch (Exception ex) {
            return heuristicDeepFallback(flowSearch, architectureSearch);
        }
    }

    private Map<String, Object> buildQualitySynthesis(Analysis analysis, Map<String, Object> pipelineResult) {
        Map<String, Object> synthesis = new LinkedHashMap<>();
        String systemPrompt = """
                Produce a compact executive-quality synthesis from pipeline outputs.
                Return strict JSON with keys:
                - finalAssessment: one paragraph
                - strongestSignals: array of up to 5 bullet strings
                - likelyBlindSpots: array of up to 4 bullet strings
                - immediateNextActions: array of up to 5 bullet strings
                Ensure every point is implementation-grounded.
                No markdown fences.
                """;
        String userPrompt = """
                Repository: %s/%s
                Pipeline result JSON:
                %s
                """.formatted(
                analysis.getRepo().getOwner(),
                analysis.getRepo().getName(),
                trimForPrompt(toJson(pipelineResult), 14_000)
        );

        try {
            AiGenerationResponse response = executeGenerationCall(analysis, "QUALITY_SYNTHESIS", systemPrompt, userPrompt, 650);
            Map<String, Object> root = objectMapper.readValue(stripMarkdownFences(response.text()), new TypeReference<>() {});
            synthesis.put("status", "COMPLETED");
            synthesis.put("finalAssessment", String.valueOf(root.getOrDefault("finalAssessment", "Assessment unavailable.")));
            synthesis.put("strongestSignals", ensureNonEmptyList(
                    listOfStrings(root.get("strongestSignals")),
                    extractFallbackSignals(pipelineResult)
            ));
            synthesis.put("likelyBlindSpots", ensureNonEmptyList(
                    listOfStrings(root.get("likelyBlindSpots")),
                    extractFallbackBlindSpots(pipelineResult)
            ));
            synthesis.put("immediateNextActions", ensureNonEmptyList(
                    listOfStrings(root.get("immediateNextActions")),
                    extractFallbackActions(pipelineResult)
            ));
            return synthesis;
        } catch (Exception firstError) {
            try {
                String compactPrompt = """
                        Write a concise final assessment and 3 concrete next actions from this pipeline output.
                        Include one blind spot.
                        Output plain text only in this exact format:
                        ASSESSMENT: ...
                        ACTIONS:
                        - ...
                        - ...
                        - ...
                        BLIND_SPOT: ...
                        """;
                AiGenerationResponse retry = executeGenerationCall(
                        analysis,
                        "QUALITY_SYNTHESIS_RETRY",
                        compactPrompt,
                        userPrompt,
                        320
                );
                String text = stripMarkdownFences(retry.text());
                synthesis.put("status", "FALLBACK");
                synthesis.put("error", firstError.getMessage());
                synthesis.put("finalAssessment", extractSection(text, "ASSESSMENT:", "ACTIONS:", "Assessment unavailable."));
                synthesis.put("strongestSignals", extractFallbackSignals(pipelineResult));
                synthesis.put("likelyBlindSpots", List.of(extractSection(text, "BLIND_SPOT:", null, "Limited provider synthesis depth.")));
                synthesis.put("immediateNextActions", ensureNonEmptyList(extractBullets(text), extractFallbackActions(pipelineResult)));
                return synthesis;
            } catch (Exception secondError) {
                synthesis.put("status", "FALLBACK");
                synthesis.put("error", firstError.getMessage() + " | retry: " + secondError.getMessage());
                synthesis.put("finalAssessment", buildDeterministicAssessment(pipelineResult));
                synthesis.put("strongestSignals", extractFallbackSignals(pipelineResult));
                synthesis.put("likelyBlindSpots", extractFallbackBlindSpots(pipelineResult));
                synthesis.put("immediateNextActions", extractFallbackActions(pipelineResult));
                return synthesis;
            }
        }
    }

    private DeepSynthesisOutput heuristicDeepFallback(
            RetrievalService.SearchResult flowSearch,
            RetrievalService.SearchResult architectureSearch
    ) {
        List<String> flowPaths = flowSearch.chunks().stream().map(RetrievalService.SearchChunk::path).distinct().limit(5).toList();
        List<String> archPaths = architectureSearch.chunks().stream().map(RetrievalService.SearchChunk::path).distinct().limit(5).toList();
        String flowNarrative = flowPaths.isEmpty()
                ? "- [evidence: unavailable] Flow evidence was limited; request lifecycle could not be fully grounded."
                : "- [evidence: " + String.join(", ", flowPaths) + "] Runtime flow enters through "
                + flowPaths.get(0) + " and propagates through worker/queue paths.";
        String architectureNarrative = archPaths.isEmpty()
                ? "- [evidence: unavailable] Architecture evidence was limited; component boundaries remain partial."
                : "- [evidence: " + String.join(", ", archPaths) + "] Architecture boundaries cluster around runtime handlers and concurrency primitives.";
        List<String> risks = new ArrayList<>();
        if (!flowPaths.isEmpty()) {
            risks.add("Flow coupling risk across runtime-critical paths: " + String.join(", ", flowPaths));
        }
        if (!archPaths.isEmpty()) {
            risks.add("Boundary drift risk between components in: " + String.join(", ", archPaths));
        }
        if (risks.isEmpty()) {
            risks.add("Insufficient high-confidence evidence for detailed risk extraction.");
        }
        List<String> actions = List.of(
                "Increase targeted indexing depth for entrypoint, service, and persistence paths.",
                "Add deterministic trace extraction for call-path evidence and persistence boundaries.",
                "Run deep synthesis with stable provider SLA when runtime budget permits."
        );
        return new DeepSynthesisOutput(
                flowNarrative,
                architectureNarrative,
                risks,
                actions,
                "MEDIUM"
        );
    }

    private String joinBullets(List<String> items, String fallback) {
        if (items.isEmpty()) {
            return fallback;
        }
        return items.stream().map(v -> "- " + v).collect(Collectors.joining("\n"));
    }

    private List<String> enforceEvidenceBullets(List<String> bullets, List<String> fallbackEvidencePaths, String fallbackClaim) {
        List<String> cleanBullets = bullets.stream()
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .map(v -> v.startsWith("- ") ? v.substring(2).trim() : v)
                .limit(7)
                .toList();

        List<String> enriched = new ArrayList<>();
        for (String bullet : cleanBullets) {
            if (bullet.toLowerCase(Locale.ROOT).startsWith("[evidence:")) {
                enriched.add(bullet);
            } else {
                String evidence = fallbackEvidencePaths.isEmpty()
                        ? "unavailable"
                        : String.join(", ", fallbackEvidencePaths.stream().limit(2).toList());
                enriched.add("[evidence: " + evidence + "] " + bullet);
            }
        }

        if (enriched.isEmpty()) {
            String evidence = fallbackEvidencePaths.isEmpty()
                    ? "unavailable"
                    : String.join(", ", fallbackEvidencePaths.stream().limit(3).toList());
            enriched = List.of("[evidence: " + evidence + "] " + fallbackClaim);
        }
        return enriched;
    }

    private <T> List<T> ensureNonEmptyList(List<T> value, List<T> fallback) {
        if (value != null && !value.isEmpty()) {
            return value;
        }
        return fallback;
    }

    private List<String> listOfStrings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.trim());
        }
        return List.of();
    }

    private String extractSection(String text, String startToken, String endToken, String fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        int start = text.indexOf(startToken);
        if (start < 0) {
            return fallback;
        }
        start += startToken.length();
        int end = endToken == null ? -1 : text.indexOf(endToken, start);
        String section = end >= 0 ? text.substring(start, end) : text.substring(start);
        String normalized = section.trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private List<String> extractBullets(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("\\r?\\n"))
                .map(String::trim)
                .filter(line -> line.startsWith("- "))
                .map(line -> line.substring(2).trim())
                .filter(line -> !line.isBlank())
                .distinct()
                .limit(6)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stageMap(Map<String, Object> pipelineResult, String stageName) {
        Object value = pipelineResult.get(stageName);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String buildDeterministicAssessment(Map<String, Object> pipelineResult) {
        Map<String, Object> overview = stageMap(pipelineResult, "OVERVIEW");
        Map<String, Object> architecture = stageMap(pipelineResult, "ARCHITECTURE");
        Map<String, Object> dataFlow = stageMap(pipelineResult, "DATA_FLOW");
        String repoName = String.valueOf(overview.getOrDefault("repoName", "repository"));
        Object tech = overview.getOrDefault("techStack", List.of());
        String stack = tech instanceof List<?> list && !list.isEmpty()
                ? list.stream().map(String::valueOf).limit(4).collect(Collectors.joining(", "))
                : "mixed stack";
        String entry = String.valueOf(overview.getOrDefault("entryPoint", "unknown entrypoint"));
        String style = String.valueOf(architecture.getOrDefault("style", "unknown architecture style"));
        List<String> controllers = listOfStrings(dataFlow.get("controllers"));
        String flow = controllers.isEmpty() ? "runtime flow candidates are partial" : "runtime likely enters via " + controllers.get(0);
        return "Analysis completed with deterministic fallback synthesis for " + repoName
                + ": stack=" + stack + ", style=" + style + ", entrypoint=" + entry + ", and " + flow + ".";
    }

    private List<String> extractFallbackSignals(Map<String, Object> pipelineResult) {
        Map<String, Object> overview = stageMap(pipelineResult, "OVERVIEW");
        Map<String, Object> dataFlow = stageMap(pipelineResult, "DATA_FLOW");
        Map<String, Object> embedding = stageMap(pipelineResult, "EMBEDDING");
        List<String> signals = new ArrayList<>();
        signals.add("Detected stack signals: " + String.valueOf(overview.getOrDefault("techStack", List.of())));
        signals.add("Entrypoint candidate: " + String.valueOf(overview.getOrDefault("entryPoint", "unknown")));
        signals.add("Primary flow candidates: " + listOfStrings(dataFlow.get("controllers")).stream().limit(3).collect(Collectors.joining(", ")));
        signals.add("Embedding coverage: files=" + embedding.getOrDefault("embeddedFiles", 0)
                + ", chunks=" + embedding.getOrDefault("totalChunks", 0));
        return signals.stream().filter(s -> !s.endsWith(": ")).distinct().limit(5).toList();
    }

    private List<String> extractFallbackBlindSpots(Map<String, Object> pipelineResult) {
        Map<String, Object> dataFlow = stageMap(pipelineResult, "DATA_FLOW");
        Map<String, Object> deepPass = stageMap(pipelineResult, "DEEP_PASS");
        List<String> blindSpots = new ArrayList<>();
        if (listOfStrings(dataFlow.get("repositories")).isEmpty()) {
            blindSpots.add("Persistence/data-access evidence is limited for definitive flow tracing.");
        }
        if ("DEGRADED".equalsIgnoreCase(String.valueOf(deepPass.getOrDefault("status", "")))) {
            blindSpots.add("Deep-pass synthesis degraded due to provider/runtime constraints.");
        }
        blindSpots.add("Path-based inference still has uncertainty without full call-graph extraction.");
        return blindSpots.stream().distinct().limit(4).toList();
    }

    private List<String> extractFallbackActions(Map<String, Object> pipelineResult) {
        Map<String, Object> dataFlow = stageMap(pipelineResult, "DATA_FLOW");
        Map<String, Object> embedding = stageMap(pipelineResult, "EMBEDDING");
        List<String> actions = new ArrayList<>();
        actions.add("Increase runtime-path weighting for entrypoint, worker, and data-access files.");
        actions.add("Add deterministic call-path extraction for request lifecycle evidence.");
        actions.add("Expand risk scoring with code-level signals (locks, exception handling, input validation).");
        if ("DEGRADED".equalsIgnoreCase(String.valueOf(embedding.getOrDefault("status", "")))) {
            actions.add("Stabilize embedding stage retries and reduce chunk pressure near deadline.");
        }
        if (listOfStrings(dataFlow.get("controllers")).size() <= 1) {
            actions.add("Broaden flow candidate discovery for non-framework projects.");
        }
        return actions.stream().distinct().limit(5).toList();
    }

    private String normalizeConfidence(Object value) {
        String raw = value == null ? "" : value.toString().trim().toUpperCase(Locale.ROOT);
        if (Set.of("HIGH", "MEDIUM", "LOW").contains(raw)) {
            return raw;
        }
        return "MEDIUM";
    }

    private long remainingMs(PipelineContext ctx) {
        return Math.max(0L, ctx.deadlineEpochMs() - System.currentTimeMillis());
    }

    private QualityProfile resolveQualityProfile(int fileCount) {
        String normalized = defaultQualityProfile.trim().toUpperCase(Locale.ROOT);
        if ("FAST".equals(normalized)) return QualityProfile.FAST;
        if ("DEEP".equals(normalized)) return QualityProfile.DEEP;
        if ("BALANCED".equals(normalized)) return QualityProfile.BALANCED;
        if (fileCount <= 180) return QualityProfile.DEEP;
        if (fileCount <= 650) return QualityProfile.BALANCED;
        return QualityProfile.FAST;
    }

    private String normalizeForCompare(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String trimForPrompt(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars);
    }

    private List<String> filterByAnyKeyword(List<FileNode> files, List<String> keywords, int limit, boolean excludeInfraPaths) {
        Set<String> loweredKeywords = keywords.stream()
                .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        return files.stream()
                .map(FileNode::getPath)
                .filter(path -> {
                    String lower = path.toLowerCase(Locale.ROOT);
                    if (excludeInfraPaths && isInfraPath(lower)) {
                        return false;
                    }
                    return loweredKeywords.stream().anyMatch(keyword -> pathMatchesKeyword(lower, keyword));
                })
                .sorted(Comparator.comparingInt(this::dataFlowPathPriority).reversed())
                .limit(limit)
                .toList();
    }

    private boolean isInfraPath(String lowerPath) {
        if (lowerPath == null || lowerPath.isBlank()) {
            return false;
        }
        if (lowerPath.startsWith("docker/")
                || lowerPath.startsWith(".vscode/")
                || lowerPath.startsWith(".idea/")
                || lowerPath.startsWith("node_modules/")) {
            return true;
        }
        return INFRA_PATH_HINTS.stream().anyMatch(lowerPath::contains);
    }

    private boolean isDocumentationPath(String lowerPath) {
        if (lowerPath == null || lowerPath.isBlank()) {
            return false;
        }
        return DOC_PATH_HINTS.stream().anyMatch(lowerPath::contains);
    }

    private boolean isTestPath(String lowerPath) {
        if (lowerPath == null || lowerPath.isBlank()) {
            return false;
        }
        return TEST_PATH_HINTS.stream().anyMatch(lowerPath::contains);
    }

    private boolean isModuleCandidate(FileNode file) {
        String lower = file.getPath().toLowerCase(Locale.ROOT);
        if (isInfraPath(lower) || isDocumentationPath(lower) || isTestPath(lower) || isBuildManifestPath(lower)) {
            return false;
        }
        return true;
    }

    private boolean isRuntimeFlowCandidate(FileNode file) {
        String lower = file.getPath().toLowerCase(Locale.ROOT);
        if (isInfraPath(lower) || isDocumentationPath(lower) || isTestPath(lower) || isBuildManifestPath(lower)) {
            return false;
        }
        return true;
    }

    private boolean isDataAccessCandidate(FileNode file) {
        String lower = file.getPath().toLowerCase(Locale.ROOT);
        if (isInfraPath(lower) || isDocumentationPath(lower) || isTestPath(lower) || isBuildManifestPath(lower)) {
            return false;
        }
        return pathMatchesKeyword(lower, "db")
                || pathMatchesKeyword(lower, "sql")
                || pathMatchesKeyword(lower, "store")
                || pathMatchesKeyword(lower, "repository")
                || pathMatchesKeyword(lower, "dao")
                || pathMatchesKeyword(lower, "mysql")
                || pathMatchesKeyword(lower, "postgres")
                || pathMatchesKeyword(lower, "redis")
                || pathMatchesKeyword(lower, "qdrant")
                || lower.endsWith(".sql");
    }

    private boolean isRiskCandidate(FileNode file) {
        String lower = file.getPath().toLowerCase(Locale.ROOT);
        if (isDocumentationPath(lower) || isInfraPath(lower) || isTestPath(lower) || isBuildManifestPath(lower)) {
            return false;
        }
        return true;
    }

    private boolean isControllerLikePath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (isInfraPath(lower) || isDocumentationPath(lower) || isTestPath(lower)) {
            return false;
        }
        if (isRequestDtoPath(lower)) {
            return false;
        }
        return pathMatchesKeyword(lower, "controller")
                || pathMatchesKeyword(lower, "route")
                || pathMatchesKeyword(lower, "router")
                || pathMatchesKeyword(lower, "endpoint")
                || pathMatchesKeyword(lower, "handler")
                || pathMatchesKeyword(lower, "server")
                || lower.endsWith("application.java");
    }

    private boolean isServiceLikePath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (isInfraPath(lower) || isDocumentationPath(lower) || isTestPath(lower)) {
            return false;
        }
        if (isRequestDtoPath(lower)) {
            return false;
        }
        return pathMatchesKeyword(lower, "service")
                || pathMatchesKeyword(lower, "manager")
                || pathMatchesKeyword(lower, "orchestrator")
                || pathMatchesKeyword(lower, "worker")
                || pathMatchesKeyword(lower, "processor")
                || lower.contains("thread_pool");
    }

    private boolean isRepositoryLikePath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (isInfraPath(lower) || isDocumentationPath(lower) || isTestPath(lower)) {
            return false;
        }
        return pathMatchesKeyword(lower, "repository")
                || pathMatchesKeyword(lower, "dao")
                || pathMatchesKeyword(lower, "store")
                || pathMatchesKeyword(lower, "database")
                || pathMatchesKeyword(lower, "mysql")
                || pathMatchesKeyword(lower, "postgres")
                || pathMatchesKeyword(lower, "redis")
                || pathMatchesKeyword(lower, "qdrant")
                || lower.endsWith(".sql");
    }

    private boolean isRequestDtoPath(String lowerPath) {
        String fileName = lowerPath.contains("/") ? lowerPath.substring(lowerPath.lastIndexOf('/') + 1) : lowerPath;
        return (fileName.contains("request") || fileName.contains("response"))
                && !fileName.contains("controller")
                && !fileName.contains("handler");
    }

    private boolean isBuildManifestPath(String lowerPath) {
        String fileName = lowerPath.contains("/") ? lowerPath.substring(lowerPath.lastIndexOf('/') + 1) : lowerPath;
        return fileName.equals("package.json")
                || fileName.equals("package-lock.json")
                || fileName.equals("yarn.lock")
                || fileName.equals("pnpm-lock.yaml")
                || fileName.equals("pom.xml")
                || fileName.equals("build.gradle")
                || fileName.equals("settings.gradle");
    }

    private boolean pathMatchesKeyword(String lowerPath, String keyword) {
        String normalizedKeyword = keyword.toLowerCase(Locale.ROOT);
        if (normalizedKeyword.contains("_")) {
            return lowerPath.contains(normalizedKeyword);
        }
        List<String> tokens = pathTokens(lowerPath);
        if (tokens.isEmpty()) {
            return lowerPath.contains(normalizedKeyword);
        }
        if (normalizedKeyword.length() <= 2) {
            return tokens.stream().anyMatch(token ->
                    token.equals(normalizedKeyword)
                            || token.startsWith(normalizedKeyword)
                            || token.endsWith(normalizedKeyword)
            );
        }
        return tokens.stream().anyMatch(token ->
                token.equals(normalizedKeyword)
                        || token.startsWith(normalizedKeyword)
                        || token.endsWith(normalizedKeyword)
                        || token.contains(normalizedKeyword)
        );
    }

    private List<String> pathTokens(String lowerPath) {
        return Arrays.stream(lowerPath.split("[^a-z0-9]+"))
                .filter(token -> !token.isBlank())
                .toList();
    }

    private int entrypointPriority(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int score = 0;
        if (ENTRYPOINT_HINTS.stream().anyMatch(lower::endsWith)) score += 60;
        if (lower.contains("/src/")) score += 18;
        if (lower.contains("server")) score += 20;
        if (lower.endsWith("cmakelists.txt")) score += 8;
        if (isInfraPath(lower) || isDocumentationPath(lower)) score -= 30;
        if (isTestPath(lower)) score -= 35;
        return score;
    }

    private int modulePathPriority(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int score = 0;
        if (pathMatchesKeyword(lower, "controller") || pathMatchesKeyword(lower, "handler") || pathMatchesKeyword(lower, "router")) score += 40;
        if (pathMatchesKeyword(lower, "service") || pathMatchesKeyword(lower, "manager") || lower.contains("thread_pool")) score += 38;
        if (pathMatchesKeyword(lower, "repository") || pathMatchesKeyword(lower, "dao") || pathMatchesKeyword(lower, "db") || lower.endsWith(".sql")) score += 34;
        if (pathMatchesKeyword(lower, "model") || pathMatchesKeyword(lower, "entity") || pathMatchesKeyword(lower, "domain")) score += 30;
        if (pathMatchesKeyword(lower, "server") || lower.endsWith("/main.cpp") || lower.endsWith("/main.java")) score += 34;
        if (lower.contains("/src/") || lower.contains("/include/")) score += 16;
        if (lower.endsWith(".hpp") || lower.endsWith(".h") || lower.endsWith(".cpp") || lower.endsWith(".java")) score += 12;
        if (lower.contains("/www/")) score += 8;
        if (isDocumentationPath(lower)) score -= 18;
        if (isInfraPath(lower)) score -= 26;
        if (isTestPath(lower)) score -= 24;
        return score;
    }

    private List<String> highestPriorityPaths(List<FileNode> files, int limit) {
        return files.stream()
                .map(FileNode::getPath)
                .sorted(Comparator.comparingInt(this::dataFlowPathPriority).reversed())
                .limit(limit)
                .toList();
    }

    private int dataFlowPathPriority(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int score = 0;
        if (pathMatchesKeyword(lower, "controller") || pathMatchesKeyword(lower, "handler") || pathMatchesKeyword(lower, "router")) score += 30;
        if (pathMatchesKeyword(lower, "service") || pathMatchesKeyword(lower, "manager") || lower.contains("thread_pool")) score += 25;
        if (pathMatchesKeyword(lower, "repository") || pathMatchesKeyword(lower, "dao") || pathMatchesKeyword(lower, "db")) score += 22;
        if (pathMatchesKeyword(lower, "server") || lower.endsWith("/main.cpp") || lower.endsWith("/main.java")) score += 20;
        if (pathMatchesKeyword(lower, "queue") || pathMatchesKeyword(lower, "socket") || pathMatchesKeyword(lower, "http")) score += 18;
        if (isRequestDtoPath(lower)) score -= 20;
        if (isTestPath(lower)) score -= 22;
        if (isDocumentationPath(lower)) score -= 20;
        if (isInfraPath(lower)) score -= 24;
        return score;
    }

    private boolean isAnalyzableTextFile(FileNode file) {
        long size = Optional.ofNullable(file.getSizeBytes()).orElse(0L);
        if (size == 0L) {
            return true;
        }
        if (size > MAX_EMBED_FILE_SIZE_BYTES) {
            return false;
        }
        String ext = getExtension(file);
        return TEXT_EXTENSIONS.contains(ext);
    }

    private String inferRole(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String fileName = lower.substring(lower.lastIndexOf('/') + 1);

        if (isRequestDtoPath(lower)) return "Defines request/response payload schema for API interaction.";
        if (lower.contains("controller") || lower.contains("router") || lower.contains("route")) return "Handles incoming API routes and request dispatching.";
        if (lower.contains("handler")) return "Implements request handling and response generation logic.";
        if (lower.contains("service") || lower.contains("manager") || lower.contains("orchestrator")) return "Coordinates core business logic across components.";
        if (lower.contains("repository") || lower.contains("dao") || lower.contains("store")) return "Implements persistence reads/writes and data lookup operations.";
        if (lower.contains("thread_pool")) return "Manages worker-thread lifecycle and queued task execution.";
        if (lower.contains("task_queue") || lower.contains("queue")) return "Provides synchronized task queue primitives for concurrent processing.";
        if (lower.contains("config")) return "Defines runtime configuration, dependencies, or environment wiring.";
        if (lower.endsWith("application.java")) return "Bootstraps Spring Boot application startup and component scanning.";
        if (fileName.equals("server.cpp") || fileName.equals("main.cpp")) return "Initializes network server loop and request-accept pipeline.";
        if (lower.contains("dto")) return "Defines request/response data contracts between layers.";
        if (lower.contains("entity") || lower.contains("domain")) return "Represents core domain model and persisted state.";
        if (lower.contains("test")) return "Validates runtime behavior with targeted test coverage.";
        if (lower.endsWith("docker-compose.yml")) return "Defines local multi-service runtime topology and service dependencies.";
        if (lower.endsWith("readme.md")) return "Documents setup, usage, and operational workflow for contributors.";
        return GENERIC_ROLE;
    }

    private String inferLanguage(String path) {
        String ext = getExtensionFromPath(path);
        return switch (ext) {
            case "java" -> "Java";
            case "js", "jsx" -> "JavaScript";
            case "ts", "tsx" -> "TypeScript";
            case "py" -> "Python";
            case "go" -> "Go";
            case "c", "cpp", "h", "hpp" -> "C/C++";
            case "md" -> "Markdown";
            case "sql" -> "SQL";
            case "yml", "yaml" -> "YAML";
            default -> "Unknown";
        };
    }

    private String mapExtensionToTech(String extension) {
        return switch (extension) {
            case "java" -> "Java";
            case "js", "jsx" -> "JavaScript";
            case "ts", "tsx" -> "TypeScript";
            case "py" -> "Python";
            case "sql" -> "SQL";
            case "yml", "yaml" -> "YAML";
            case "xml" -> "XML";
            case "md" -> "Markdown";
            default -> extension.toUpperCase(Locale.ROOT);
        };
    }

    private String getExtension(FileNode file) {
        return getExtensionFromPath(file.getPath());
    }

    private String getExtensionFromPath(String path) {
        int idx = path.lastIndexOf('.');
        if (idx < 0 || idx == path.length() - 1) return "no_ext";
        return path.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private int estimateTokens(Map<String, Object> output) {
        int chars = toJson(output).length();
        return Math.max(20, chars / 4);
    }

    private String runStageSummary(Analysis analysis, String stageName, String systemPrompt, String userPrompt, int maxTokens) {
        if (!stageAiSummaryEnabled) {
            return "Summary generated from structural heuristics (AI summary disabled for fast mode).";
        }
        try {
            AiGenerationResponse response = executeGenerationCall(analysis, stageName, systemPrompt, userPrompt, maxTokens);
            String text = response.text() == null ? "" : response.text().trim();
            return text.isBlank() ? "Summary not available." : text;
        } catch (Exception ex) {
            log.warn("AI summary call failed for stage={}, analysisId={}. Using fallback.", stageName, analysis.getId(), ex);
            return "Summary generated with fallback due to AI provider failure.";
        }
    }

    private String buildOverviewHeuristicSummary(
            Repo repo,
            int fileCount,
            int directoryCount,
            List<String> techStack,
            String entryPoint
    ) {
        String stack = techStack.isEmpty() ? "mixed stack" : String.join(", ", techStack);
        String entry = (entryPoint == null || entryPoint.isBlank()) ? "no clear entrypoint detected" : ("entrypoint appears to be `" + entryPoint + "`");
        return "Repository `" + repo.getName() + "` has " + fileCount + " files across "
                + directoryCount + " directories, with dominant technologies: " + stack + "; " + entry + ".";
    }

    private String buildArchitectureHeuristicSummary(Set<String> topLevelModules) {
        if (topLevelModules.isEmpty()) {
            return "Top-level module structure is minimal; architecture boundaries are currently limited.";
        }
        List<String> runtime = topLevelModules.stream()
                .filter(module -> List.of("src", "include", "app", "server", "backend").contains(module.toLowerCase(Locale.ROOT)))
                .toList();
        List<String> support = topLevelModules.stream()
                .filter(module -> !runtime.contains(module))
                .toList();
        return "Architecture is modular-monolith oriented with runtime modules "
                + (runtime.isEmpty() ? topLevelModules : runtime)
                + " and support modules " + support + ".";
    }

    private String buildModuleHeuristicSummary(List<Map<String, Object>> modules) {
        Map<String, Long> byArea = modules.stream()
                .map(module -> String.valueOf(module.getOrDefault("path", "")))
                .map(path -> path.contains("/") ? path.substring(0, path.indexOf('/')) : path)
                .collect(Collectors.groupingBy(area -> area, LinkedHashMap::new, Collectors.counting()));
        String split = byArea.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(Collectors.joining(", "));
        return "Module responsibilities are distributed across: " + split
                + ", with core runtime logic concentrated in source/include paths.";
    }

    private String buildDataFlowHeuristicSummary(List<String> controllers, List<String> services, List<String> repositories) {
        if (controllers.isEmpty() && services.isEmpty() && repositories.isEmpty()) {
            return "No clear runtime flow candidates were identified from path heuristics.";
        }
        String entry = controllers.isEmpty() ? "entrypoint candidates are unclear" : "runtime entry appears through " + controllers.get(0);
        String serviceLayer = services.isEmpty() ? "service orchestration is embedded in core runtime files" : "service-equivalent logic appears in " + String.join(", ", services);
        String dataLayer = repositories.isEmpty() ? "data/persistence files are minimal" : "data access appears in " + String.join(", ", repositories);
        return entry + "; " + serviceLayer + "; " + dataLayer + ".";
    }

    private String buildRiskHeuristicSummary(List<Map<String, Object>> risks) {
        if (risks.isEmpty()) {
            return "No major risk files identified from current heuristics.";
        }
        return risks.stream()
                .limit(3)
                .map(risk -> String.valueOf(risk.getOrDefault("file", "unknown")) + " (" + risk.getOrDefault("risk", "UNKNOWN") + ")")
                .collect(Collectors.joining("; ", "Top risk candidates: ", "."));
    }

    private Map<String, Object> buildRiskEntry(FileNode file) {
        String lower = file.getPath().toLowerCase(Locale.ROOT);
        long sizeBytes = Optional.ofNullable(file.getSizeBytes()).orElse(0L);
        int score = 0;
        List<String> reasons = new ArrayList<>();
        boolean lockFilePath = lower.contains("package-lock")
                || lower.endsWith("yarn.lock")
                || lower.endsWith("pnpm-lock.yaml")
                || lower.endsWith("composer.lock");

        if (!isRequestDtoPath(lower)
                && (pathMatchesKeyword(lower, "server")
                || pathMatchesKeyword(lower, "handler")
                || pathMatchesKeyword(lower, "router")
                || pathMatchesKeyword(lower, "controller")
                || pathMatchesKeyword(lower, "endpoint"))) {
            score += 35;
            reasons.add("Request-entrypoint logic can propagate defects across all runtime requests.");
        }
        if (!lockFilePath && (
                pathMatchesKeyword(lower, "thread")
                        || pathMatchesKeyword(lower, "queue")
                        || pathMatchesKeyword(lower, "pool")
                        || pathMatchesKeyword(lower, "mutex")
                        || pathMatchesKeyword(lower, "lock")
        )) {
            score += 34;
            reasons.add("Concurrency primitives increase race-condition and deadlock risk.");
        }
        if (pathMatchesKeyword(lower, "socket")
                || pathMatchesKeyword(lower, "http")
                || pathMatchesKeyword(lower, "net")
                || pathMatchesKeyword(lower, "connection")) {
            score += 20;
            reasons.add("Network I/O paths are sensitive to timeout and malformed-input handling.");
        }
        if (pathMatchesKeyword(lower, "auth")
                || pathMatchesKeyword(lower, "jwt")
                || pathMatchesKeyword(lower, "token")
                || pathMatchesKeyword(lower, "crypto")
                || pathMatchesKeyword(lower, "password")
                || pathMatchesKeyword(lower, "secret")) {
            score += 32;
            reasons.add("Security-sensitive path requires strict validation and secret handling.");
        }
        if (pathMatchesKeyword(lower, "repository")
                || pathMatchesKeyword(lower, "dao")
                || pathMatchesKeyword(lower, "store")
                || pathMatchesKeyword(lower, "db")
                || pathMatchesKeyword(lower, "sql")
                || pathMatchesKeyword(lower, "mysql")
                || pathMatchesKeyword(lower, "postgres")) {
            score += 24;
            reasons.add("Persistence/data-access layer can impact consistency and data integrity.");
        }
        if (sizeBytes > 40_000) {
            score += 12;
            reasons.add("Large file size may hide tightly coupled logic and weak testability.");
        } else if (sizeBytes > 8_000) {
            score += 6;
        }
        if (isTestPath(lower) || isDocumentationPath(lower) || isInfraPath(lower)) {
            score -= 40;
        }

        String level = score >= 60 ? "HIGH" : (score >= 30 ? "MEDIUM" : "LOW");
        if (reasons.isEmpty()) {
            reasons.add("Central runtime location indicates moderate change impact risk.");
        }

        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("file", file.getPath());
        risk.put("sizeBytes", sizeBytes);
        risk.put("riskScore", Math.max(0, score));
        risk.put("risk", level);
        risk.put("reason", reasons.get(0));
        risk.put("signals", reasons.stream().distinct().limit(4).toList());
        return risk;
    }

    private Map<String, String> generateFileSummaries(Analysis analysis, List<FileNode> batch) {
        String systemPrompt = """
                You summarize code files based on path and metadata.
                Output strict JSON object mapping file path to one-line role summary.
                Do not use generic phrases like "contributes to repository functionality".
                Keep each summary below 16 words and describe concrete responsibility.
                """;
        String userPrompt = "Files:\n" + batch.stream()
                .map(file -> "- " + file.getPath() + " | lang=" + Optional.ofNullable(file.getLanguage()).orElse("unknown"))
                .collect(Collectors.joining("\n"));
        try {
            AiGenerationResponse response = executeGenerationCall(analysis, "FILE_ANNOTATION", systemPrompt, userPrompt, 1200);
            String cleaned = stripMarkdownFences(response.text());
            return objectMapper.readValue(cleaned, new TypeReference<>() {});
        } catch (Exception ex) {
            log.warn("File summary generation failed for analysisId={}. Using heuristic fallback.", analysis.getId(), ex);
            return batch.stream().collect(Collectors.toMap(FileNode::getPath, file -> inferRole(file.getPath())));
        }
    }

    private AiGenerationResponse executeGenerationCall(Analysis analysis, String stageName, String systemPrompt, String userPrompt, int maxTokens) {
        String model = "meta/llama-3.1-70b-instruct";
        AiGenerationRequest request = new AiGenerationRequest(
                analysis.getAiProvider(),
                model,
                systemPrompt,
                userPrompt,
                0.1,
                maxTokens
        );
        long started = System.currentTimeMillis();
        try {
            AiGenerationResponse response = aiProviderRouter.resolve(analysis.getAiProvider()).generate(request);
            persistAiCallLog(analysis, stageName, model, response.usage().inputTokens(), response.usage().outputTokens(), System.currentTimeMillis() - started, true, null);
            return response;
        } catch (Exception ex) {
            persistAiCallLog(analysis, stageName, model, 0, 0, System.currentTimeMillis() - started, false, ex.getMessage());
            throw ex;
        }
    }

    private AiEmbeddingResponse executeEmbeddingCall(Analysis analysis, String stageName, String input) {
        String model = "nvidia/nv-embedqa-e5-v5";
        AiEmbeddingRequest request = new AiEmbeddingRequest(analysis.getAiProvider(), model, input, "passage");
        long started = System.currentTimeMillis();
        try {
            AiEmbeddingResponse response = aiProviderRouter.resolve(analysis.getAiProvider()).embed(request);
            persistAiCallLog(analysis, stageName, model, response.usage().inputTokens(), response.usage().outputTokens(), System.currentTimeMillis() - started, true, null);
            return response;
        } catch (Exception ex) {
            persistAiCallLog(analysis, stageName, model, 0, 0, System.currentTimeMillis() - started, false, ex.getMessage());
            throw ex;
        }
    }

    private void persistAiCallLog(
            Analysis analysis,
            String stageName,
            String model,
            int inputTokens,
            int outputTokens,
            long durationMs,
            boolean success,
            String errorMsg
    ) {
        String safeError = errorMsg == null ? null : (stageName + ": " + errorMsg);
        AiCallLog logEntry = AiCallLog.builder()
                .user(analysis.getUser())
                .analysis(analysis)
                .provider(analysis.getAiProvider())
                .model(model)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .durationMs(durationMs)
                .success(success)
                .errorMsg(safeError)
                .build();
        aiCallLogRepository.save(logEntry);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize stage output", e);
        }
    }

    private String stripMarkdownFences(String input) {
        if (input == null) {
            return "";
        }
        String trimmed = input.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int endFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && endFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, endFence).trim();
            }
        }
        return trimmed;
    }

    private String normalizeProvider(String aiProviderInput) {
        if (aiProviderInput == null || aiProviderInput.isBlank()) {
            return "NVIDIA_DEV";
        }
        return aiProviderInput.trim().toUpperCase(Locale.ROOT);
    }

    private record StageDefinition(int number, String name) {
    }

    private record StageExecutionResult(Map<String, Object> output, int tokensUsed) {
    }

    private record PipelineContext(
            long deadlineEpochMs,
            QualityProfile qualityProfile
    ) {
    }

    private enum QualityProfile {
        FAST,
        BALANCED,
        DEEP
    }

    private record DeepSynthesisOutput(
            String flowNarrative,
            String architectureNarrative,
            List<String> keyRisks,
            List<String> improvementActions,
            String confidence
    ) {
    }
}
