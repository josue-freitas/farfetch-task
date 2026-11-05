package com.example.availability.gateway.http;

import com.example.availability.model.ContextKey;
import com.example.availability.model.ProductContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductContextSnapshotReaderTest {

    private final ProductContextSnapshotReader reader = new ProductContextSnapshotReader(
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));

    @Test
    void keepsOnlyTheContextsReferencedByTheUpload() throws IOException {
        String json = "[" + context("style-2", "size-36") + "," + context("style-1", "size-34") + ","
                + context("style-3", "size-38") + "]";
        ContextKey wanted = new ContextKey("style-1", "size-34", "PT");

        Map<ContextKey, ProductContext> result = reader.read(stream(json), Set.of(wanted));

        assertEquals(1, result.size());
        assertEquals("Linen Shirt 1", result.get(wanted).productName());
        assertEquals("EU 34", result.get(wanted).sizeLabel());
    }

    @Test
    void rejectsANonArraySnapshot() {
        assertThrows(IOException.class, () -> reader.read(stream("{\"not\":\"an array\"}"), Set.of()));
    }

    private static String context(String productId, String sizeId) {
        return """
                {"productId":"%s","productName":"Linen Shirt %s",
                 "brand":{"id":"brand-42","name":"Atelier Example"},
                 "category":{"id":"cat-shirts","name":"Shirts"},
                 "color":{"id":"color-blue","name":"Blue"},
                 "productStatus":"ACTIVE","sizeId":"%s","sizeLabel":"EU %s","sizeSystem":"EU",
                 "countryCode":"PT","countryEnabled":true,"someFutureField":1}
                """.formatted(productId, productId.replace("style-", ""), sizeId, sizeId.replace("size-", ""));
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
