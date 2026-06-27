package com.repomind.backend.service.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class GitHubRateLimitTracker {

    // Optimistic defaults — updated after every GitHub response
    private final AtomicLong remaining = new AtomicLong(5000);
    private final AtomicLong resetEpochSeconds = new AtomicLong(0);

    public void update(long newRemaining, long newResetEpochSeconds) {
        remaining.set(newRemaining);
        resetEpochSeconds.set(newResetEpochSeconds);
        if (newRemaining < 100) {
            log.warn("GitHub rate limit low: {} calls remaining, resets at epoch {}", newRemaining, newResetEpochSeconds);
        }
    }

    public long getRemaining() {
        return remaining.get();
    }

    /**
     * Blocks the calling thread if fewer than 2 calls remain in the current window.
     * Each ingestion needs 2 calls (getRepoInfo + getTree), so we hold off until the
     * window resets rather than letting the worker proceed and fail mid-flight.
     */
    public void awaitCapacityIfNeeded() throws InterruptedException {
        if (remaining.get() >= 2) return;

        long now = Instant.now().getEpochSecond();
        long resetAt = resetEpochSeconds.get();
        long sleepMs = Math.max(0, (resetAt - now) + 2) * 1000L;

        log.warn("GitHub rate limit exhausted ({} remaining). Worker pausing {}ms until window resets.",
                remaining.get(), sleepMs);
        Thread.sleep(sleepMs);

        // Optimistically reset — the next API call will give us accurate headers
        remaining.set(5000);
    }
}
