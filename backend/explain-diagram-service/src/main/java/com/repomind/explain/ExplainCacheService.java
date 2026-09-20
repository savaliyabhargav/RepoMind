package com.repomind.explain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repomind.explain.domain.FileExplainCache;
import com.repomind.explain.domain.FileExplainCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable diagram cache keyed by (canonical repo, path, content hash): a file
 * version is paid for once across all users. Writes run in their own
 * transaction because callers hold a read-only one.
 */
@Service
public class ExplainCacheService {

    private static final Logger log = LoggerFactory.getLogger(ExplainCacheService.class);

    private final FileExplainCacheRepository cacheRepository;
    private final ObjectMapper objectMapper;

    public ExplainCacheService(FileExplainCacheRepository cacheRepository, ObjectMapper objectMapper) {
        this.cacheRepository = cacheRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<FileExplainCache> find(UUID canonicalRepoId, String path, String contentHash) {
        return cacheRepository.findByCanonicalRepoIdAndPathAndContentHash(canonicalRepoId, path, contentHash);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(UUID canonicalRepoId, String path, String contentHash,
                     String provider, String model,
                     String diagramType, String mermaidCode, String summary, List<String> concepts) {
        try {
            FileExplainCache entry = cacheRepository
                    .findByCanonicalRepoIdAndPathAndContentHash(canonicalRepoId, path, contentHash)
                    .orElseGet(() -> FileExplainCache.builder()
                            .canonicalRepoId(canonicalRepoId)
                            .path(path)
                            .contentHash(contentHash)
                            .build());
            entry.setProvider(provider);
            entry.setModel(model);
            entry.setDiagramType(diagramType);
            entry.setMermaidCode(mermaidCode);
            entry.setSummary(summary);
            entry.setConceptsJson(writeConcepts(concepts));
            cacheRepository.save(entry);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent request cached the same key first — nothing lost.
            log.debug("Explain cache insert raced for path={} hash={}", path, contentHash);
        }
    }

    public List<String> readConcepts(FileExplainCache entry) {
        if (entry.getConceptsJson() == null || entry.getConceptsJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(entry.getConceptsJson(), new TypeReference<>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String writeConcepts(List<String> concepts) {
        try {
            return objectMapper.writeValueAsString(concepts == null ? List.of() : concepts);
        } catch (Exception ex) {
            return "[]";
        }
    }

    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
