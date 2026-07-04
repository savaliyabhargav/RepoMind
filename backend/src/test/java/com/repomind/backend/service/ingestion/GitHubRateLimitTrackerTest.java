package com.repomind.backend.service.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class GitHubRateLimitTrackerTest {

    private GitHubRateLimitTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new GitHubRateLimitTracker();
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void update_setsRemainingCorrectly() {
        tracker.update(42L, Instant.now().getEpochSecond() + 3600);
        assertThat(tracker.getRemaining()).isEqualTo(42L);
    }

    @Test
    void update_overwritesPreviousValue() {
        tracker.update(100L, Instant.now().getEpochSecond() + 3600);
        tracker.update(7L, Instant.now().getEpochSecond() + 3600);
        assertThat(tracker.getRemaining()).isEqualTo(7L);
    }

    // ── awaitCapacityIfNeeded ─────────────────────────────────────────────────

    @Test
    void awaitCapacityIfNeeded_doesNotBlockWhenRemainingIsSufficient() throws InterruptedException {
        tracker.update(500L, Instant.now().getEpochSecond() + 3600);

        long start = System.currentTimeMillis();
        tracker.awaitCapacityIfNeeded();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(200); // should return instantly
    }

    @Test
    void awaitCapacityIfNeeded_doesNotBlockWhenRemainingEqualsTwo() throws InterruptedException {
        tracker.update(2L, Instant.now().getEpochSecond() + 3600);

        long start = System.currentTimeMillis();
        tracker.awaitCapacityIfNeeded();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(200);
    }

    @Test
    void awaitCapacityIfNeeded_blocksAndResetsWhenExhausted() throws InterruptedException {
        // Set reset 1 second in the past — sleep will be 0ms (Math.max(0, ...))
        long pastReset = Instant.now().getEpochSecond() - 5;
        tracker.update(1L, pastReset);

        long start = System.currentTimeMillis();
        tracker.awaitCapacityIfNeeded();
        long elapsed = System.currentTimeMillis() - start;

        // Should not block long since reset is in the past; remaining resets to 5000
        assertThat(elapsed).isLessThan(1000);
        assertThat(tracker.getRemaining()).isEqualTo(5000L);
    }

    @Test
    void awaitCapacityIfNeeded_resetsToCeilingAfterWait() throws InterruptedException {
        long pastReset = Instant.now().getEpochSecond() - 10;
        tracker.update(0L, pastReset);

        tracker.awaitCapacityIfNeeded();

        assertThat(tracker.getRemaining()).isEqualTo(5000L);
    }

    // ── initial state ─────────────────────────────────────────────────────────

    @Test
    void initialRemainingIsHighEnoughToNotBlock() {
        // Default is 5000, so no blocking expected
        assertThat(tracker.getRemaining()).isGreaterThanOrEqualTo(2L);
    }
}
