package com.example.availability.support;

import com.example.availability.model.ContextKey;
import com.example.availability.model.ProductContext;
import com.example.availability.model.StockKey;
import com.example.availability.model.StockUpdate;
import com.example.availability.util.RetryPolicy;

import java.time.Duration;

/**
 * Builders for test data.
 */
public final class TestData {

    private TestData() {
    }

    public static ProductContext context(String productId, String sizeId, String countryCode,
                                         String status, boolean countryEnabled) {
        return new ProductContext(
                productId,
                "Linen Shirt " + productId.replace("style-", ""),
                new ProductContext.Reference("brand-42", "Atelier Example"),
                new ProductContext.Reference("cat-shirts", "Shirts"),
                new ProductContext.Reference("color-blue", "Blue"),
                status,
                sizeId,
                "EU " + sizeId.replace("size-", ""),
                "EU",
                countryCode,
                countryEnabled);
    }

    public static ProductContext activeContext(ContextKey key) {
        return context(key.productId(), key.sizeId(), key.countryCode(), ProductContext.ACTIVE, true);
    }

    public static StockUpdate update(String sellerId, String productId, String sizeId, int quantity, long version) {
        return new StockUpdate(new StockKey(sellerId, productId, sizeId, "PT"), quantity, version, "test");
    }

    /**
     * Retries without waiting; any Retry-After is treated as "too long" (no retry).
     */
    public static RetryPolicy noWaitRetries(int maxAttempts) {
        return new RetryPolicy(maxAttempts, Duration.ZERO, Duration.ZERO, delay -> {
        });
    }
}
