package com.repomind.backend.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    // Find a token by its hash to verify if it's valid
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // Delete a specific token (used during logout or rotation)
    void deleteByTokenHash(String tokenHash);

    // Clean up all tokens for a user
    void deleteByUser(User user);
}