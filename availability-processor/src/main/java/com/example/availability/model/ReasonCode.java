package com.example.availability.model;

/**
 * Machine-readable reason attached to every non-successful processing result.
 */
public enum ReasonCode {
    /**
     * The input failed validation.
     */
    INVALID_INPUT,
    /**
     * A newer version is already applied for the same stock key.
     */
    STALE_VERSION,
    /**
     * The same version is already applied and its output event already published.
     */
    DUPLICATE_VERSION,
    /**
     * The Product Context API does not know (productId, sizeId, countryCode).
     */
    PRODUCT_CONTEXT_NOT_FOUND,
    /**
     * The product exists but its status is not ACTIVE.
     */
    PRODUCT_INACTIVE,
    /**
     * The product exists but is not enabled in the requested country.
     */
    PRODUCT_NOT_ENABLED_IN_COUNTRY,
    /**
     * The Product Context API (single lookup or snapshot) could not be used.
     */
    PRODUCT_CONTEXT_UNAVAILABLE,
    /**
     * The Availability API rejected the update with a non-retryable status.
     */
    AVAILABILITY_UPDATE_REJECTED,
    /**
     * The Availability API kept failing with transient errors.
     */
    AVAILABILITY_UPDATE_FAILED,
    /**
     * The availability write succeeded but the output event could not be published.
     */
    EVENT_PUBLISH_FAILED,
    /**
     * A bug or an unexpected exception.
     */
    UNEXPECTED_ERROR
}
