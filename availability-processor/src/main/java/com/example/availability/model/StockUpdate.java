package com.example.availability.model;

/**
 * A validated stock change. Both input paths (inventory events and CSV rows) are converted into
 * this type so they can share exactly the same processing flow.
 *
 * @param origin human-readable description of where the update came from, used in logs only
 */
public record StockUpdate(StockKey key, int availableQuantity, long sourceVersion, String origin) {
}
