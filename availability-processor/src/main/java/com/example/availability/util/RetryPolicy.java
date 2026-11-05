package com.example.availability.util;

import com.example.availability.exception.TransientDependencyException;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Bounded in-process retry for transient dependency failures.
 * <ul>
 *     <li>Only {@link TransientDependencyException} is retried (429, 5xx, IO errors).</li>
 *     <li>Without {@code Retry-After}: exponential backoff starting at {@code initialBackoff},
 *     capped at {@code maxBackoff}.</li>
 *     <li>With {@code Retry-After}: we wait exactly that long, never less. If the dependency asks
 *     for more than {@code maxBackoff}, we stop retrying in-process and hand the failure back to the
 *     caller (broker redelivery / job report) instead of blocking a worker thread.</li>
 * </ul>
 */
public final class RetryPolicy {

    private static final Logger LOG = Logger.getLogger(RetryPolicy.class);

    /**
     * Abstraction over {@link Thread#sleep} so tests do not have to wait.
     */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Sleeper sleeper;

    public RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, Sleeper sleeper) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.sleeper = sleeper;
    }

    public <T> T call(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (TransientDependencyException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                Duration delay = delayBeforeNextAttempt(attempt, e);
                if (delay == null) {
                    LOG.warnf("Not retrying %s: Retry-After %s exceeds the in-process limit %s",
                            e.dependency(), e.retryAfter().orElse(null), maxBackoff);
                    throw e;
                }
                LOG.debugf("Attempt %d/%d to %s failed (%s); retrying in %d ms",
                        attempt, maxAttempts, e.dependency(), e.getMessage(), delay.toMillis());
                sleep(delay, e);
            }
        }
    }

    public void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }

    /**
     * Returns the delay before the next attempt, or {@code null} when we should stop retrying.
     */
    Duration delayBeforeNextAttempt(int failedAttempt, TransientDependencyException failure) {
        if (failure.retryAfter().isPresent()) {
            Duration requested = failure.retryAfter().get();
            return requested.compareTo(maxBackoff) <= 0 ? requested : null;
        }
        long exponential = initialBackoff.toMillis() * (1L << Math.min(failedAttempt - 1, 20));
        return Duration.ofMillis(Math.min(exponential, maxBackoff.toMillis()));
    }

    private void sleep(Duration delay, TransientDependencyException failure) {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure;
        }
    }
}
