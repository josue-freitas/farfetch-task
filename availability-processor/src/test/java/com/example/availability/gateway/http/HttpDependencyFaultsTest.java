package com.example.availability.gateway.http;

import com.example.availability.dto.external.AvailabilityUpdateRequest;
import com.example.availability.dto.external.SizeAvailabilityUpdated;
import com.example.availability.exception.ProductContextNotFoundException;
import com.example.availability.exception.TransientDependencyException;
import com.example.availability.gateway.AvailabilityGateway;
import com.example.availability.gateway.EventPublisher;
import com.example.availability.gateway.ProductContextProvider;
import com.example.availability.model.ContextKey;
import com.example.availability.model.ProductContext;
import com.example.availability.model.StockKey;
import com.example.availability.support.WireMockResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks that every documented WireMock fault input is classified correctly by our HTTP adapters
 * (no retries at this level: retries are applied by the core flow).
 */
@QuarkusTest
@QuarkusTestResource(WireMockResource.class)
class HttpDependencyFaultsTest {

    @Inject
    ProductContextProvider productContexts;

    @Inject
    AvailabilityGateway availabilityGateway;

    @Inject
    EventPublisher eventPublisher;

    @Test
    void productContextLookupMapsTheProvidedResponses() {
        ProductContext context = productContexts.findOne(new ContextKey("style-123", "size-38", "PT"));
        assertEquals("Linen Shirt 123", context.productName());
        assertEquals("EU 38", context.sizeLabel());
        assertEquals("ACTIVE", context.productStatus());

        assertFalse(productContexts.findOne(new ContextKey("style-9999", "size-38", "PT")).isActive());
        assertFalse(productContexts.findOne(new ContextKey("style-9998", "size-38", "PT")).isEnabledInCountry());

        assertThrows(ProductContextNotFoundException.class,
                () -> productContexts.findOne(new ContextKey("style-50000", "size-38", "PT")));
        TransientDependencyException throttled = assertThrows(TransientDependencyException.class,
                () -> productContexts.findOne(new ContextKey("style-90001", "size-38", "PT")));
        assertEquals(Duration.ofSeconds(5), throttled.retryAfter().orElseThrow());
        assertThrows(TransientDependencyException.class,
                () -> productContexts.findOne(new ContextKey("style-90002", "size-38", "PT")));
    }

    @Test
    void snapshotIsStreamedAndFilteredToTheWantedKeys() {
        ContextKey wanted = new ContextKey("style-2237", "size-42", "PT");

        Map<ContextKey, ProductContext> result = productContexts.fetchSnapshot("PT", Set.of(wanted));

        assertEquals(1, result.size());
        assertEquals("Linen Shirt 2237", result.get(wanted).productName());
    }

    @Test
    void availabilityGatewayMapsTheProvidedFaults() {
        AvailabilityUpdateRequest request = new AvailabilityUpdateRequest(3, 7, null);

        availabilityGateway.update(new StockKey("boutique-42", "style-123", "size-38", "PT"), request);

        TransientDependencyException throttled = assertThrows(TransientDependencyException.class,
                () -> availabilityGateway.update(new StockKey("seller-429", "style-123", "size-38", "PT"), request));
        assertEquals(Duration.ofSeconds(5), throttled.retryAfter().orElseThrow());
        assertThrows(TransientDependencyException.class,
                () -> availabilityGateway.update(new StockKey("seller-503", "style-123", "size-38", "PT"), request));
    }

    @Test
    void eventSinkMapsTheProvidedFaults() {
        eventPublisher.publish(event("out-0001"));

        TransientDependencyException throttled = assertThrows(TransientDependencyException.class,
                () -> eventPublisher.publish(event("fault-429")));
        assertEquals(Duration.ofSeconds(5), throttled.retryAfter().orElseThrow());
        assertThrows(TransientDependencyException.class, () -> eventPublisher.publish(event("fault-503")));
    }

    private static SizeAvailabilityUpdated event(String eventId) {
        return SizeAvailabilityUpdated.of(eventId, Instant.parse("2026-10-20T08:01:00Z"),
                new StockKey("boutique-42", "style-123", "size-38", "PT"), 3, 7, null);
    }
}
