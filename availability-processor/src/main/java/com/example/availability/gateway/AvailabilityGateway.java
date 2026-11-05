package com.example.availability.gateway;

import com.example.availability.dto.external.AvailabilityUpdateRequest;
import com.example.availability.model.StockKey;

/**
 * Port to the Availability API Gateway.
 */
public interface AvailabilityGateway {

    /**
     * @throws TransientDependencyException on 429 / 5xx / IO errors
     * @throws PermanentDependencyException on other 4xx answers
     */
    void update(StockKey key, AvailabilityUpdateRequest request);
}
