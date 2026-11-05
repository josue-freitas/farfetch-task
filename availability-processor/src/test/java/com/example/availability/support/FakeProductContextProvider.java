package com.example.availability.support;

import com.example.availability.exception.ProductContextNotFoundException;
import com.example.availability.exception.TransientDependencyException;
import com.example.availability.gateway.ProductContextProvider;
import com.example.availability.model.ContextKey;
import com.example.availability.model.ProductContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory Product Context API that records which kind of lookup was used.
 */
public class FakeProductContextProvider implements ProductContextProvider {

    private final Map<ContextKey, ProductContext> contexts = new ConcurrentHashMap<>();
    private final Set<String> unavailableSnapshots = ConcurrentHashMap.newKeySet();
    private final List<ContextKey> singleLookups = new CopyOnWriteArrayList<>();
    private final List<String> snapshotRequests = new CopyOnWriteArrayList<>();

    public FakeProductContextProvider with(ProductContext context) {
        contexts.put(context.key(), context);
        return this;
    }

    public void makeSnapshotUnavailable(String countryCode) {
        unavailableSnapshots.add(countryCode);
    }

    @Override
    public ProductContext findOne(ContextKey key) {
        singleLookups.add(key);
        ProductContext context = contexts.get(key);
        if (context == null) {
            throw new ProductContextNotFoundException("fake", key);
        }
        return context;
    }

    @Override
    public Map<ContextKey, ProductContext> fetchSnapshot(String countryCode, Set<ContextKey> wanted) {
        snapshotRequests.add(countryCode);
        if (unavailableSnapshots.contains(countryCode)) {
            throw new TransientDependencyException("fake", "HTTP 503", null, null);
        }
        Map<ContextKey, ProductContext> result = new HashMap<>();
        contexts.forEach((key, context) -> {
            if (key.countryCode().equals(countryCode) && wanted.contains(key)) {
                result.put(key, context);
            }
        });
        return result;
    }

    public List<ContextKey> singleLookups() {
        return singleLookups;
    }

    public List<String> snapshotRequests() {
        return snapshotRequests;
    }
}
