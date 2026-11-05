package com.example.availability.dto.external;

import com.example.availability.model.ProductContextSummary;
import com.example.availability.model.StockKey;

import java.time.Instant;

/**
 * Normalized output event published for downstream consumers.
 */
public record SizeAvailabilityUpdated(
        String eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String sellerId,
        String productId,
        String sizeId,
        String countryCode,
        int availableQuantity,
        long sourceVersion,
        ProductContextSummary productContext) {

    public static final String EVENT_TYPE = "SizeAvailabilityUpdated";
    public static final int SCHEMA_VERSION = 1;

    public static SizeAvailabilityUpdated of(String eventId, Instant occurredAt, StockKey key,
                                             int availableQuantity, long sourceVersion,
                                             ProductContextSummary productContext) {
        return new SizeAvailabilityUpdated(
                eventId,
                EVENT_TYPE,
                SCHEMA_VERSION,
                occurredAt,
                key.sellerId(),
                key.productId(),
                key.sizeId(),
                key.countryCode(),
                availableQuantity,
                sourceVersion,
                productContext);
    }
}
