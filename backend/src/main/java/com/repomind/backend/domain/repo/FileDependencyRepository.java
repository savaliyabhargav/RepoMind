package com.repomind.backend.domain.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FileDependencyRepository extends JpaRepository<FileDependency, UUID> {

    // Finds all files that a specific file depends on
    List<FileDependency> findBySourceFileId(UUID sourceFileId);

    // Finds all files that depend ON a specific file (critical for Impact Analysis)
    List<FileDependency> findByTargetFileId(UUID targetFileId);
}