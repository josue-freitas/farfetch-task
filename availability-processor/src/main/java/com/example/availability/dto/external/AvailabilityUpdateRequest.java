package com.example.availability.dto.external;

import com.example.availability.model.ProductContextSummary;

/**
 * Body of {@code PUT /v1/availability/{sellerId}/{productId}/{sizeId}?countryCode=..}.
 * {@code sourceVersion} is always sent so the gateway can also protect itself against stale writes.
 */
public record AvailabilityUpdateRequest(
        int availableQuantity,
        long sourceVersion,
        ProductContextSummary productContext) {
}
