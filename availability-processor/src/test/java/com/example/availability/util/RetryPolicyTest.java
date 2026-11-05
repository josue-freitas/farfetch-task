package com.example.availability.util;

import com.example.availability.exception.PermanentDependencyException;
import com.example.availability.exception.TransientDependencyException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryPolicyTest {

    private final List<Duration> sleeps = new ArrayList<>();
    private final RetryPolicy policy =
            new RetryPolicy(4, Duration.ofMillis(100), Duration.ofSeconds(1), sleeps::add);

    @Test
    void retriesTransientFailuresWithExponentialBackoffUntilSuccess() {
        AtomicInteger attempts = new AtomicInteger();

        String result = policy.call(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw transientFailure(null);
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(3, attempts.get());
        assertEquals(List.of(Duration.ofMillis(100), Duration.ofMillis(200)), sleeps);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(TransientDependencyException.class, () -> policy.run(() -> {
            attempts.incrementAndGet();
            throw transientFailure(null);
        }));

        assertEquals(4, attempts.get());
    }

    @Test
    void backoffIsCappedAtMaxBackoff() {
        RetryPolicy longPolicy = new RetryPolicy(10, Duration.ofMillis(400), Duration.ofSeconds(1), sleeps::add);

        assertThrows(TransientDependencyException.class, () -> longPolicy.run(() -> {
            throw transientFailure(null);
        }));

        assertEquals(Duration.ofSeconds(1), sleeps.get(sleeps.size() - 1));
    }

    @Test
    void waitsExactlyRetryAfterWhenItIsWithinTheLimit() {
        AtomicInteger attempts = new AtomicInteger();

        policy.run(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw transientFailure(Duration.ofMillis(700));
            }
        });

        assertEquals(List.of(Duration.ofMillis(700)), sleeps);
    }

    @Test
    void doesNotRetryWhenRetryAfterExceedsTheLimit() {
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(TransientDependencyException.class, () -> policy.run(() -> {
            attempts.incrementAndGet();
            throw transientFailure(Duration.ofSeconds(5));
        }));

        assertEquals(1, attempts.get());
        assertEquals(0, sleeps.size());
    }

    @Test
    void neverRetriesPermanentFailures() {
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(PermanentDependencyException.class, () -> policy.run(() -> {
            attempts.incrementAndGet();
            throw new PermanentDependencyException("dep", 400, "HTTP 400");
        }));

        assertEquals(1, attempts.get());
    }

    private static TransientDependencyException transientFailure(Duration retryAfter) {
        return new TransientDependencyException("dep", "HTTP 503", retryAfter, null);
    }
}
