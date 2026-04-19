package com.repomind.backend.domain.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RepoRepository extends JpaRepository<Repo, UUID> {

    // We can add a custom finder to check if a user has already ingested a specific URL
    Optional<Repo> findByUrlAndUserId(String url, UUID userId);
}
