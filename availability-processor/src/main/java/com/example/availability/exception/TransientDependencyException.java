package com.example.availability.exception;

import java.time.Duration;
import java.util.Optional;

/**
 * A dependency failed in a way that may succeed later: HTTP 429, HTTP 5xx, timeouts or connection
 * errors. These are the only failures that are retried.
 */
public class TransientDependencyException extends RuntimeException {

    private final String dependency;
    private final Duration retryAfter;

    public TransientDependencyException(String dependency, String message, Duration retryAfter, Throwable cause) {
        super(dependency + ": " + message, cause);
        this.dependency = dependency;
        this.retryAfter = retryAfter;
    }

    public String dependency() {
        return dependency;
    }

    /**
     * Value of the {@code Retry-After} header, when the dependency sent one.
     */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
