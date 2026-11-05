package com.example.availability.service;

import com.example.availability.model.ContextKey;
import com.example.availability.model.ProductContext;

/**
 * Strategy used by {@link AvailabilityProcessor} to obtain the product context. This is the only
 * part of the flow that differs between input paths:
 * <ul>
 *     <li>events: one HTTP lookup per event (with retries);</li>
 *     <li>bulk imports: an in-memory lookup in the pre-fetched country snapshot.</li>
 * </ul>
 */
@FunctionalInterface
public interface ContextResolver {

    ProductContext resolve(ContextKey key);
}
