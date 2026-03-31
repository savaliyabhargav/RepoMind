package com.repomind.backend.domain.user;

/**
 * Defines the subscription tiers for RepoMind users.
 * Matches the 'plan' column constraints in the users table.
 */
public enum Plan {
    FREE,
    PRO,
    ENTERPRISE
}
