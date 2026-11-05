package com.example.availability.gateway.http;

import com.example.availability.client.AvailabilityRestClient;
import com.example.availability.dto.external.AvailabilityUpdateRequest;
import com.example.availability.gateway.AvailabilityGateway;
import com.example.availability.model.StockKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * {@link AvailabilityGateway} backed by the Availability API Gateway over HTTP.
 */
@ApplicationScoped
public class HttpAvailabilityGateway implements AvailabilityGateway {

    static final String DEPENDENCY = "availability-api";

    private final AvailabilityRestClient client;

    @Inject
    public HttpAvailabilityGateway(@RestClient AvailabilityRestClient client) {
        this.client = client;
    }

    @Override
    public void update(StockKey key, AvailabilityUpdateRequest request) {
        HttpResponses.call(DEPENDENCY,
                () -> client.updateAvailability(key.sellerId(), key.productId(), key.sizeId(), key.countryCode(), request),
                response -> null);
    }
}