package com.example.availability.model;

/**
 * The subset of canonical context that is sent to the Availability API and included in the
 * output event. Identity fields (productId, sizeId, countryCode) are not repeated here because
 * they are already part of the URL / event.
 */
public record ProductContextSummary(
        String productName,
        ProductContext.Reference brand,
        ProductContext.Reference category,
        ProductContext.Reference color,
        String sizeLabel,
        String sizeSystem) {

    public static ProductContextSummary from(ProductContext context) {
        return new ProductContextSummary(
                context.productName(),
                context.brand(),
                context.category(),
                context.color(),
                context.sizeLabel(),
                context.sizeSystem());
    }
}
