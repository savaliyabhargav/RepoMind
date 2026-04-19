package com.repomind.backend.service.ingestion;

import com.repomind.backend.domain.repo.Repo;
import com.repomind.backend.domain.repo.RepoRepository;
import com.repomind.backend.domain.user.User;
import com.repomind.backend.domain.user.UserRepository;
import com.repomind.backend.service.ingestion.dto.GitHubRepoResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.repomind.backend.domain.repo.FileNode;
import com.repomind.backend.domain.repo.FileNodeRepository;
import com.repomind.backend.service.ingestion.dto.GitHubTreeResponse;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionService {

    private final GitHubApiClient gitHubApiClient;
    private final RepoRepository repoRepository;
    private final UserRepository userRepository;
    private final FileNodeRepository fileNodeRepository; // Add this dependency

    private static final Pattern GITHUB_URL_PATTERN =
            Pattern.compile("github\\.com/([^/]+)/([^/.]+)(?:\\.git)?");

    @Transactional
    public Repo ingestRepository(String url, UUID userId) {
        // ... (Keep existing Steps 1-3 from your previous version) ...
        Matcher matcher = GITHUB_URL_PATTERN.matcher(url);
        if (!matcher.find()) throw new IllegalArgumentException("Invalid URL");
        String owner = matcher.group(1);
        String repoName = matcher.group(2);

        User user = userRepository.findById(userId).orElseThrow();
        GitHubRepoResponse githubInfo = gitHubApiClient.getRepoInfo(owner, repoName).block();

        Repo repo = Repo.builder()
                .user(user)
                .url(url)
                .name(githubInfo.name())
                .owner(owner)
                .provider("GITHUB")
                .isPrivate(githubInfo.isPrivate())
                .defaultBranch(githubInfo.defaultBranch())
                .sizeKb(githubInfo.size())
                .status("INGESTING")
                .build();

        Repo savedRepo = repoRepository.save(repo);

        // 5. Fetch the full file tree and save nodes
        fetchAndSaveTree(savedRepo, owner, repoName, savedRepo.getDefaultBranch());

        // 6. Mark as Ready for Analysis
        savedRepo.setStatus("READY");
        return repoRepository.save(savedRepo);
    }

    private void fetchAndSaveTree(Repo repo, String owner, String repoName, String branch) {
        log.info("Fetching file tree for {}/{}", owner, repoName);

        GitHubTreeResponse treeResponse = gitHubApiClient.getTree(owner, repoName, branch).block();

        if (treeResponse != null && treeResponse.tree() != null) {
            List<FileNode> nodes = treeResponse.tree().stream()
                    .map(entry -> {
                        // Extract name from path (e.g., "src/main/java/App.java" -> "App.java")
                        String name = entry.path().substring(entry.path().lastIndexOf('/') + 1);
                        // Calculate depth based on path separators
                        int depth = (int) entry.path().chars().filter(ch -> ch == '/').count();

                        return FileNode.builder()
                                .repo(repo)
                                .path(entry.path())
                                .name(name)
                                .type(entry.type().equals("blob") ? "FILE" : "DIRECTORY")
                                .sizeBytes(entry.size() != null ? entry.size() : 0L) // Added null check
                                .depth(depth)
                                .isInScope(true)
                                .build();
                    })
                    .collect(Collectors.toList());

            fileNodeRepository.saveAll(nodes);
            log.info("Successfully saved {} file nodes for repo {}", nodes.size(), repo.getName());

            // Update Repo with file count
            repo.setFileCount(nodes.size());
        }
    }
}