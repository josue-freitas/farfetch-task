package com.example.availability.model;

/**
 * Lookup key of a {@link ProductContext}: (productId, sizeId, countryCode).
 */
public record ContextKey(String productId, String sizeId, String countryCode) {
}
