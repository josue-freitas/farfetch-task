package com.example.availability.dto.request;

/**
 * Inbound {@code SellerInventoryChanged} message contract. Fields are boxed/strings so that missing
 * or malformed values reach our validator (and produce a precise error) instead of failing silently.
 */
public record InventoryEventMessage(
        String eventId,
        String eventType,
        Integer schemaVersion,
        String occurredAt,
        String sellerId,
        String productId,
        String sizeId,
        String countryCode,
        Integer availableQuantity,
        Long sourceVersion) {
}
