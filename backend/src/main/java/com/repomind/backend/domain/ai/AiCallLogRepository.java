package com.repomind.backend.domain.ai;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AiCallLogRepository extends JpaRepository<AiCallLog, UUID> {
}
