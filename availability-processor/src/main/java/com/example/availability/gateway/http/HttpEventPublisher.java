package com.example.availability.gateway.http;

import com.example.availability.client.EventSinkRestClient;
import com.example.availability.dto.external.SizeAvailabilityUpdated;
import com.example.availability.gateway.EventPublisher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Local {@link EventPublisher}: POSTs the event to the WireMock sink. In production this class is
 * replaced by a broker producer (e.g. Kafka keyed by the stock key) without touching the core flow.
 */
@ApplicationScoped
public class HttpEventPublisher implements EventPublisher {

    static final String DEPENDENCY = "event-sink";

    private final EventSinkRestClient client;

    @Inject
    public HttpEventPublisher(@RestClient EventSinkRestClient client) {
        this.client = client;
    }

    @Override
    public void publish(SizeAvailabilityUpdated event) {
        HttpResponses.call(DEPENDENCY, () -> client.publish(event), response -> null);
    }
}
