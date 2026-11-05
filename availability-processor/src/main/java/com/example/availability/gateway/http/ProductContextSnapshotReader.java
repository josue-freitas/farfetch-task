package com.example.availability.gateway.http;

import com.example.availability.model.ContextKey;
import com.example.availability.model.ProductContext;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Streams a snapshot JSON array object by object and keeps only the contexts whose key was
 * requested. The whole array is never materialised as a list: one {@link ProductContext} is built at
 * a time, so memory depends on the number of wanted keys, not on the snapshot size.
 */
@ApplicationScoped
public class ProductContextSnapshotReader {

    private final ObjectMapper objectMapper;

    @Inject
    public ProductContextSnapshotReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<ContextKey, ProductContext> read(InputStream json, Set<ContextKey> wanted) throws IOException {
        Map<ContextKey, ProductContext> matches = new HashMap<>();
        try (JsonParser parser = objectMapper.getFactory().createParser(json)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IOException("product context snapshot must be a JSON array");
            }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                ProductContext context = objectMapper.readValue(parser, ProductContext.class);
                ContextKey key = context.key();
                if (wanted.contains(key)) {
                    matches.put(key, context);
                }
            }
        }
        return matches;
    }
}
