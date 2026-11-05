package com.example.availability.gateway.http;

import com.example.availability.client.ProductContextRestClient;
import com.example.availability.exception.PermanentDependencyException;
import com.example.availability.exception.ProductContextNotFoundException;
import com.example.availability.exception.TransientDependencyException;
import com.example.availability.gateway.ProductContextProvider;
import com.example.availability.model.ContextKey;
import com.example.availability.model.ProductContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * {@link ProductContextProvider} backed by the Product Context HTTP API.
 */
@ApplicationScoped
public class HttpProductContextProvider implements ProductContextProvider {

    static final String LOOKUP = "product-context";
    static final String SNAPSHOT = "product-context-snapshot";

    private final ProductContextRestClient client;
    private final ProductContextSnapshotReader snapshotReader;

    @Inject
    public HttpProductContextProvider(@RestClient ProductContextRestClient client,
                                      ProductContextSnapshotReader snapshotReader) {
        this.client = client;
        this.snapshotReader = snapshotReader;
    }

    @Override
    public ProductContext findOne(ContextKey key) {
        try {
            return HttpResponses.call(LOOKUP,
                    () -> client.getProductContext(key.productId(), key.sizeId(), key.countryCode()),
                    response -> response.readEntity(ProductContext.class));
        } catch (PermanentDependencyException e) {
            if (e.status() == 404) {
                throw new ProductContextNotFoundException(LOOKUP, key);
            }
            throw e;
        }
    }

    @Override
    public Map<ContextKey, ProductContext> fetchSnapshot(String countryCode, Set<ContextKey> wanted) {
        return HttpResponses.call(SNAPSHOT, () -> client.getSnapshot(countryCode), response -> {
            try (InputStream body = response.readEntity(InputStream.class)) {
                return snapshotReader.read(body, wanted);
            } catch (IOException e) {
                throw new TransientDependencyException(SNAPSHOT, "could not read snapshot: " + e.getMessage(), null, e);
            }
        });
    }
}
