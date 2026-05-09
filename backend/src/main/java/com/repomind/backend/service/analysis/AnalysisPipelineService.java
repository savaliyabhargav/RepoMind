package com.repomind.backend.service.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    public AnalysisPipelineService(
            AnalysisRepository analysisRepository,
            AnalysisStageRepository analysisStageRepository,
            RepoRepository repoRepository,
            UserRepository userRepository,
            FileNodeRepository fileNodeRepository,
            ObjectMapper objectMapper
    ) {
        this.analysisRepository = analysisRepository;
        this.analysisStageRepository = analysisStageRepository;
        this.repoRepository = repoRepository;
        this.userRepository = userRepository;
        this.fileNodeRepository = fileNodeRepository;
        this.objectMapper = objectMapper;
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

                Map<String, Object> stageOutput = runStage(stage, repo, repoNodes, repoFiles, analyzableFiles, pipelineResult);
                int stageTokens = estimateTokens(stageOutput);
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

    private Map<String, Object> runStage(
            AnalysisStage stage,
            Repo repo,
            List<FileNode> repoNodes,
            List<FileNode> repoFiles,
            List<FileNode> analyzableFiles,
            Map<String, Object> previousStages
    ) {
        return switch (stage.getStageName()) {
            case "OVERVIEW" -> buildOverview(repo, repoNodes, repoFiles);
            case "ARCHITECTURE" -> buildArchitecture(repoNodes);
            case "MODULE_MAPPING" -> buildModuleMap(analyzableFiles);
            case "DATA_FLOW" -> buildDataFlow(analyzableFiles);
            case "BUG_DETECTION" -> buildRiskScan(analyzableFiles);
            case "FILE_ANNOTATION" -> annotateFiles(analyzableFiles);
            case "EMBEDDING" -> buildEmbeddingStage(analyzableFiles, previousStages);
            default -> throw new IllegalStateException("Unknown stage: " + stage.getStageName());
        };
    }

    private Map<String, Object> buildOverview(Repo repo, List<FileNode> nodes, List<FileNode> files) {
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
        overview.put("summary", "Repository ingested successfully and baseline structural overview generated.");
        return overview;
    }

    private Map<String, Object> buildArchitecture(List<FileNode> nodes) {
        Set<String> topLevel = nodes.stream()
                .map(FileNode::getPath)
                .filter(path -> path.contains("/"))
                .map(path -> path.substring(0, path.indexOf('/')))
                .collect(Collectors.toCollection(TreeSet::new));

        Map<String, Object> architecture = new LinkedHashMap<>();
        architecture.put("style", "Monolith");
        architecture.put("topLevelModules", topLevel);
        architecture.put("summary", "Top-level module structure inferred from repository tree.");
        return architecture;
    }

    private Map<String, Object> buildModuleMap(List<FileNode> files) {
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
        return result;
    }

    private Map<String, Object> buildDataFlow(List<FileNode> files) {
        List<String> controllers = filterByKeyword(files, "controller", MAX_DATAFLOW_FILES_PER_LAYER);
        List<String> services = filterByKeyword(files, "service", MAX_DATAFLOW_FILES_PER_LAYER);
        List<String> repositories = filterByKeyword(files, "repository", MAX_DATAFLOW_FILES_PER_LAYER);

        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("controllers", controllers);
        flow.put("services", services);
        flow.put("repositories", repositories);
        flow.put("summary", "Probable flow: controller -> service -> repository -> database.");
        return flow;
    }

    private Map<String, Object> buildRiskScan(List<FileNode> files) {
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
        result.put("summary", "Baseline static risk scan completed.");
        return result;
    }

    private Map<String, Object> annotateFiles(List<FileNode> files) {
        List<FileNode> candidates = files.stream()
                .filter(file -> Optional.ofNullable(file.getSizeBytes()).orElse(0L) <= MAX_ANNOTATION_FILE_SIZE_BYTES)
                .toList();

        int updated = 0;
        for (FileNode file : candidates) {
            String summary = inferRole(file.getPath());
            fileNodeRepository.updateRoleSummary(file.getId(), summary);
            updated++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("annotatedFiles", updated);
        result.put("skippedLargeFiles", files.size() - candidates.size());
        result.put("maxFileSizeBytes", MAX_ANNOTATION_FILE_SIZE_BYTES);
        result.put("summary", "Role summaries generated for all files in scope.");
        return result;
    }

    private Map<String, Object> buildEmbeddingStage(List<FileNode> files, Map<String, Object> previousStages) {
        int chunkSize = 1500;
        int overlap = 200;
        int embedded = 0;
        int totalChunks = 0;

        List<FileNode> candidates = files.stream()
                .filter(file -> Optional.ofNullable(file.getSizeBytes()).orElse(0L) <= MAX_EMBED_FILE_SIZE_BYTES)
                .toList();

        for (FileNode file : candidates) {
            long size = Optional.ofNullable(file.getSizeBytes()).orElse(0L);
            int chunks = Math.max(1, (int) Math.ceil(size / (double) chunkSize));
            totalChunks += chunks;
            String embeddingId = "emb-" + file.getId();
            fileNodeRepository.updateEmbeddingId(file.getId(), embeddingId);
            embedded++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("embeddedFiles", embedded);
        result.put("totalChunks", totalChunks);
        result.put("skippedLargeFiles", files.size() - candidates.size());
        result.put("maxFileSizeBytes", MAX_EMBED_FILE_SIZE_BYTES);
        result.put("chunkSizeChars", chunkSize);
        result.put("overlapChars", overlap);
        result.put("summary", "Embedding IDs assigned. Vectorization contract is ready for provider integration.");
        result.put("dependsOn", previousStages.keySet());
        return result;
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize stage output", e);
        }
    }

    private String normalizeProvider(String aiProviderInput) {
        if (aiProviderInput == null || aiProviderInput.isBlank()) {
            return "NVIDIA_DEV";
        }
        return aiProviderInput.trim().toUpperCase(Locale.ROOT);
    }

    private record StageDefinition(int number, String name) {
    }
}
