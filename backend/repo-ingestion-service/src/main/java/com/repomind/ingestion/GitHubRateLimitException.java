package com.repomind.ingestion;

public class GitHubRateLimitException extends RuntimeException {

    private final long resetEpochSeconds;

    public GitHubRateLimitException(long resetEpochSeconds) {
        super("GitHub rate limit exceeded, resets at epoch " + resetEpochSeconds);
        this.resetEpochSeconds = resetEpochSeconds;
    }

    public long getResetEpochSeconds() {
        return resetEpochSeconds;
    }
}
