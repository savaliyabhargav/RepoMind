package com.repomind.backend.api.repo;

import com.repomind.backend.domain.repo.Repo;
import com.repomind.backend.service.ingestion.IngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/repo")
@RequiredArgsConstructor
public class RepoController {

    private final IngestionService ingestionService;

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@RequestBody Map<String, String> request) {
        try {
            String url = request.get("url");
            String userIdStr = request.get("userId");

            if (url == null || userIdStr == null) {
                return ResponseEntity.badRequest().body("Missing 'url' or 'userId' in request body");
            }

            UUID userId = UUID.fromString(userIdStr);
            Repo result = ingestionService.ingestRepository(url, userId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Ingestion failed: " + e.getMessage());
        }
    }
}