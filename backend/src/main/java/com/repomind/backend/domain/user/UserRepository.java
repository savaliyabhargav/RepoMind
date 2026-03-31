package com.repomind.backend.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Used during the OAuth2 flow to check if a user already exists.
     */
    Optional<User> findByGithubId(Long githubId);

    /**
     * Used for login and profile lookups.
     */
    Optional<User> findByUsername(String username);
}
