package com.example.availability.model;

/**
 * Identity of one stock stream. {@code sourceVersion} is monotonic only within the same key,
 * so versions of different keys are never compared with each other.
 */
public record StockKey(String sellerId, String productId, String sizeId, String countryCode) {

    /**
     * The product context does not depend on the seller, so the seller is dropped from the lookup key.
     */
    public ContextKey contextKey() {
        return new ContextKey(productId, sizeId, countryCode);
    }

    @Override
    public String toString() {
        return sellerId + "/" + productId + "/" + sizeId + "/" + countryCode;
    }
}
