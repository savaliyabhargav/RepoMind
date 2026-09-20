package com.repomind.ingestion.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RepoRepository extends JpaRepository<Repo, UUID> {

    Optional<Repo> findByUrlAndUserId(String url, UUID userId);

    List<Repo> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
