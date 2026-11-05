package com.example.availability.model;

/**
 * Canonical product context returned by the Product Context API (single lookup and snapshot share
 * this model).
 */
public record ProductContext(
        String productId,
        String productName,
        Reference brand,
        Reference category,
        Reference color,
        String productStatus,
        String sizeId,
        String sizeLabel,
        String sizeSystem,
        String countryCode,
        Boolean countryEnabled) {

    public static final String ACTIVE = "ACTIVE";

    /**
     * An (id, name) pair such as a brand, category or color.
     */
    public record Reference(String id, String name) {
    }

    public ContextKey key() {
        return new ContextKey(productId, sizeId, countryCode);
    }

    public boolean isActive() {
        return ACTIVE.equals(productStatus);
    }

    /**
     * A missing flag is treated as "not enabled": we only sell where the catalogue says we can.
     */
    public boolean isEnabledInCountry() {
        return Boolean.TRUE.equals(countryEnabled);
    }
}
