package com.example.availability.config;

import com.example.availability.util.RetryPolicy;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;

/**
 * CDI producers for framework-free classes used by the core flow.
 */
@Singleton
public class AppConfig {

    @Produces
    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }

    @Produces
    @Singleton
    RetryPolicy retryPolicy(@ConfigProperty(name = "retry.max-attempts", defaultValue = "3") int maxAttempts,
                            @ConfigProperty(name = "retry.initial-backoff", defaultValue = "200ms") Duration initialBackoff,
                            @ConfigProperty(name = "retry.max-backoff", defaultValue = "5s") Duration maxBackoff) {
        return new RetryPolicy(maxAttempts, initialBackoff, maxBackoff, delay -> Thread.sleep(delay.toMillis()));
    }
}
