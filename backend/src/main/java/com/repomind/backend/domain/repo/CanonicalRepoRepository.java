package com.repomind.backend.domain.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CanonicalRepoRepository extends JpaRepository<CanonicalRepo, UUID> {

    Optional<CanonicalRepo> findByOwnerAndRepoNameAndProvider(String owner, String repoName, String provider);
}
