package com.example.availability.util;

import com.example.availability.model.StockKey;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Output event ids are derived deterministically from (stock key, sourceVersion). Republishing the
 * same business change (after a failed publish) therefore produces the same eventId, so downstream
 * consumers can de-duplicate on it.
 */
public final class OutputEventIds {

    private OutputEventIds() {
    }

    public static String forStockVersion(StockKey key, long sourceVersion) {
        String name = String.join("|", key.sellerId(), key.productId(), key.sizeId(), key.countryCode(),
                Long.toString(sourceVersion));
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
