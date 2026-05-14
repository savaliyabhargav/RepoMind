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
    private static final int MAX_DATAFLOW_FILES_PER_LAYER = 12;
    private static final int MAX_RISK_FILES = 8;

    private final AnalysisRepository analysisRepository;
    private final AnalysisStageRepository analysisStageRepository;
    private final RepoRepository repoRepository;
    private final UserRepository userRepository;
    private final FileNodeRepository fileNodeRepository;
    private final AiProviderRouter aiProviderRouter;
    private final AiCallLogRepository aiCallLogRepository;
    private final ObjectMapper objectMapper;
    private final RetrievalService retrievalService;

    public AnalysisPipelineService(
            AnalysisRepository analysisRepository,
            AnalysisStageRepository analysisStageRepository,
            RepoRepository repoRepository,
            UserRepository userRepository,
            FileNodeRepository fileNodeRepository,
            AiProviderRouter aiProviderRouter,
            AiCallLogRepository aiCallLogRepository,
            ObjectMapper objectMapper,
            RetrievalService retrievalService
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

        Map<String, Object> pipelineResult = new LinkedHashMap<>();
        int totalTokens = 0;

        try {
            for (AnalysisStage stage : stages) {
                stage.setStatus("RUNNING");
                stage.setStartedAt(Instant.now());
                analysisStageRepository.save(stage);

                analysis.setCurrentStage(stage.getStageNumber());
                analysisRepository.save(analysis);

                StageExecutionResult stageExecutionResult = runStage(stage, analysis, repo, repoNodes, repoFiles, analyzableFiles, pipelineResult);
                Map<String, Object> stageOutput = stageExecutionResult.output();
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
            Map<String, Object> previousStages
    ) {
        return switch (stage.getStageName()) {
            case "OVERVIEW" -> buildOverview(analysis, repo, repoNodes, repoFiles);
            case "ARCHITECTURE" -> buildArchitecture(analysis, repoNodes);
            case "MODULE_MAPPING" -> buildModuleMap(analysis, analyzableFiles);
            case "DATA_FLOW" -> buildDataFlow(analysis, analyzableFiles);
            case "BUG_DETECTION" -> buildRiskScan(analysis, analyzableFiles);
            case "FILE_ANNOTATION" -> annotateFiles(analysis, analyzableFiles);
            case "EMBEDDING" -> buildEmbeddingStage(analysis, analyzableFiles, previousStages);
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
                .filter(path -> path.endsWith("Application.java") || path.endsWith("main.py") || path.endsWith("index.js"))
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
        overview.put("summary", runStageSummary(
                analysis,
                "OVERVIEW",
                """
                        Create a concise summary in 1-2 sentences about repository purpose and stack.
                        Return plain text only.
                        """,
                "Repo context: " + toJson(overview),
                400
        ));
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
        architecture.put("summary", runStageSummary(
                analysis,
                "ARCHITECTURE",
                """
                        Infer architecture style and explain module boundaries in max 2 sentences.
                        Return plain text only.
                        """,
                "Top-level modules: " + topLevel,
                400
        ));
        return new StageExecutionResult(architecture, 0);
    }

    private StageExecutionResult buildModuleMap(Analysis analysis, List<FileNode> files) {
        List<Map<String, Object>> modules = files.stream()
                .sorted(Comparator.comparing(FileNode::getPath))
                .limit(30)
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
        String stageSummary = runStageSummary(
                analysis,
                "MODULE_MAPPING",
                """
                        Review module list and provide one short paragraph describing responsibility split.
                        Return plain text only.
                        """,
                toJson(modules),
                500
        );
        result.put("summary", stageSummary);
        return new StageExecutionResult(result, 0);
    }

    private StageExecutionResult buildDataFlow(Analysis analysis, List<FileNode> files) {
        List<String> controllers = filterByKeyword(files, "controller", MAX_DATAFLOW_FILES_PER_LAYER);
        List<String> services = filterByKeyword(files, "service", MAX_DATAFLOW_FILES_PER_LAYER);
        List<String> repositories = filterByKeyword(files, "repository", MAX_DATAFLOW_FILES_PER_LAYER);

        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("controllers", controllers);
        flow.put("services", services);
        flow.put("repositories", repositories);
        flow.put("summary", runStageSummary(
                analysis,
                "DATA_FLOW",
                """
                        Based on controller/service/repository file lists, describe probable request lifecycle.
                        Keep to 2 sentences.
                        """,
                toJson(flow),
                450
        ));
        return new StageExecutionResult(flow, 0);
    }

    private StageExecutionResult buildRiskScan(Analysis analysis, List<FileNode> files) {
        List<Map<String, Object>> risks = files.stream()
                .sorted(Comparator.comparingLong((FileNode file) -> Optional.ofNullable(file.getSizeBytes()).orElse(0L)).reversed())
                .limit(MAX_RISK_FILES)
                .map(file -> {
                    Map<String, Object> risk = new LinkedHashMap<>();
                    risk.put("file", file.getPath());
                    risk.put("sizeBytes", file.getSizeBytes());
                    risk.put("risk", file.getSizeBytes() != null && file.getSizeBytes() > 40_000 ? "HIGH" : "MEDIUM");
                    risk.put("reason", "Large file size or central-layer location indicates higher change risk.");
                    return risk;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("riskAreas", risks);
        result.put("summary", runStageSummary(
                analysis,
                "BUG_DETECTION",
                """
                        Provide top 3 technical risks from the provided risk areas.
                        Keep concise. Return plain text only.
                        """,
                toJson(risks),
                500
        ));
        return new StageExecutionResult(result, 0);
    }

    private StageExecutionResult annotateFiles(Analysis analysis, List<FileNode> files) {
        List<FileNode> candidates = files.stream()
                .filter(file -> Optional.ofNullable(file.getSizeBytes()).orElse(0L) <= MAX_ANNOTATION_FILE_SIZE_BYTES)
                .toList();

        int updated = 0;
        int tokensUsed = 0;
        for (int i = 0; i < candidates.size(); i += 20) {
            List<FileNode> batch = candidates.subList(i, Math.min(i + 20, candidates.size()));
            Map<String, String> summaries = generateFileSummaries(analysis, batch);
            for (FileNode file : batch) {
                String summary = summaries.getOrDefault(file.getPath(), inferRole(file.getPath()));
                fileNodeRepository.updateRoleSummary(file.getId(), summary);
                updated++;
            }
            tokensUsed += summaries.size() * 16;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("annotatedFiles", updated);
        result.put("skippedLargeFiles", files.size() - candidates.size());
        result.put("maxFileSizeBytes", MAX_ANNOTATION_FILE_SIZE_BYTES);
        result.put("summary", "Role summaries generated and persisted.");
        return new StageExecutionResult(result, tokensUsed);
    }

    private StageExecutionResult buildEmbeddingStage(Analysis analysis, List<FileNode> files, Map<String, Object> previousStages) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dependsOn", previousStages.keySet());
        try {
            RetrievalService.IndexResult indexResult = retrievalService.indexRepo(
                    analysis.getRepo().getId(),
                    analysis.getUser().getId(),
                    analysis.getAiProvider()
            );
            result.put("embeddedFiles", indexResult.embeddedFiles());
            result.put("totalChunks", indexResult.embeddedChunks());
            result.put("skippedFiles", indexResult.skippedFiles());
            result.put("chunkSizeChars", indexResult.chunkSizeChars());
            result.put("overlapChars", indexResult.chunkOverlapChars());
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

    private List<String> filterByKeyword(List<FileNode> files, String keyword, int limit) {
        return files.stream()
                .map(FileNode::getPath)
                .filter(path -> path.toLowerCase(Locale.ROOT).contains(keyword))
                .limit(limit)
                .toList();
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
        if (lower.contains("controller")) return "Handles API request routing and endpoint orchestration.";
        if (lower.contains("service")) return "Contains business logic and cross-module orchestration.";
        if (lower.contains("repository")) return "Provides data access operations for persistence.";
        if (lower.contains("config")) return "Defines framework or application configuration.";
        if (lower.endsWith("application.java")) return "Bootstraps the Spring application context.";
        if (lower.contains("dto")) return "Defines data transfer shape between layers.";
        if (lower.contains("entity") || lower.contains("domain")) return "Represents persistent domain model.";
        return "Contributes to repository functionality and module behavior.";
    }

    private String inferLanguage(String path) {
        String ext = getExtensionFromPath(path);
        return switch (ext) {
            case "java" -> "Java";
            case "js", "jsx" -> "JavaScript";
            case "ts", "tsx" -> "TypeScript";
            case "py" -> "Python";
            case "go" -> "Go";
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
        try {
            AiGenerationResponse response = executeGenerationCall(analysis, stageName, systemPrompt, userPrompt, maxTokens);
            String text = response.text() == null ? "" : response.text().trim();
            return text.isBlank() ? "Summary not available." : text;
        } catch (Exception ex) {
            log.warn("AI summary call failed for stage={}, analysisId={}. Using fallback.", stageName, analysis.getId(), ex);
            return "Summary generated with fallback due to AI provider failure.";
        }
    }

    private Map<String, String> generateFileSummaries(Analysis analysis, List<FileNode> batch) {
        String systemPrompt = """
                You summarize code files based on path and metadata.
                Output strict JSON object mapping file path to one-line role summary.
                Keep each summary below 18 words.
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
}
