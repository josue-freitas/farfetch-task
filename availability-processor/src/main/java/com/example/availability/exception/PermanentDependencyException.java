package com.example.availability.exception;

/**
 * A dependency answered with a non-retryable error (4xx other than 429). Retrying the same request
 * would give the same answer, so it is never retried.
 */
public class PermanentDependencyException extends RuntimeException {

    private final String dependency;
    private final int status;

    public PermanentDependencyException(String dependency, int status, String message) {
        super(dependency + ": " + message);
        this.dependency = dependency;
        this.status = status;
    }

    public String dependency() {
        return dependency;
    }

    public int status() {
        return status;
    }
}
