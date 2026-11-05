package com.example.availability.gateway;

import com.example.availability.model.ContextKey;
import com.example.availability.model.ProductContext;

import java.util.Map;
import java.util.Set;

/** Port to the Product Context API. */
public interface ProductContextProvider {

    /**
     * Single lookup, used by the event flow.
     *
     * @throws ProductContextNotFoundException when the API answers 404
     * @throws TransientDependencyException    on 429 / 5xx / IO errors
     */
    ProductContext findOne(ContextKey key);

    /**
     * Downloads the country snapshot (one HTTP call) and returns only the contexts whose key is in
     * {@code wanted}, so memory is bounded by the upload size instead of the catalogue size.
     */
    Map<ContextKey, ProductContext> fetchSnapshot(String countryCode, Set<ContextKey> wanted);
}
