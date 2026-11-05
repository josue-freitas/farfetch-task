package com.example.availability.gateway.http;

import com.example.availability.exception.PermanentDependencyException;
import com.example.availability.exception.TransientDependencyException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Turns HTTP outcomes into the application's failure model:
 * <ul>
 *     <li>2xx: success;</li>
 *     <li>429, 5xx, timeouts, connection errors: {@link TransientDependencyException} (retryable);</li>
 *     <li>any other status: {@link PermanentDependencyException} (not retryable).</li>
 * </ul>
 */
final class HttpResponses {

    private HttpResponses() {
    }

    static <T> T call(String dependency, Supplier<Response> request, Function<Response, T> onSuccess) {
        Response response;
        try {
            response = request.get();
        } catch (WebApplicationException e) {
            // Only happens if the REST client's default exception mapper is enabled; handle it anyway.
            response = e.getResponse();
        } catch (ProcessingException e) {
            throw new TransientDependencyException(dependency, "request failed: " + e.getMessage(), null, e);
        }
        return handle(dependency, response, onSuccess);
    }

    private static <T> T handle(String dependency, Response response, Function<Response, T> onSuccess) {
        try (response) {
            int status = response.getStatus();
            if (status >= 200 && status < 300) {
                return onSuccess.apply(response);
            }
            if (status == 429 || status >= 500) {
                throw new TransientDependencyException(dependency, "HTTP " + status, retryAfter(response), null);
            }
            throw new PermanentDependencyException(dependency, status, "HTTP " + status);
        }
    }

    /**
     * Parses {@code Retry-After} in its delta-seconds form; other forms are ignored (normal backoff).
     */
    static Duration retryAfter(Response response) {
        String value = response.getHeaderString("Retry-After");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
