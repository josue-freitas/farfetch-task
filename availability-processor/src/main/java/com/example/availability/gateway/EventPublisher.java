package com.example.availability.gateway;

import com.example.availability.dto.external.SizeAvailabilityUpdated;

/**
 * Port to the output broker. Locally it is implemented with the WireMock HTTP sink; in production
 * it would be a broker producer (see README).
 */
public interface EventPublisher {

    /**
     * @throws TransientDependencyException on 429 / 5xx / IO errors
     * @throws PermanentDependencyException on other 4xx answers
     */
    void publish(SizeAvailabilityUpdated event);
}
