package com.example.availability.exception;

import com.example.availability.model.ContextKey;

/**
 * No product context exists for (productId, sizeId, countryCode).
 */
public class ProductContextNotFoundException extends PermanentDependencyException {

    public ProductContextNotFoundException(String dependency, ContextKey key) {
        super(dependency, 404, "no product context for productId=" + key.productId()
                + ", sizeId=" + key.sizeId() + ", countryCode=" + key.countryCode());
    }
}
