package com.example.availability.model;

/**
 * What we know about one stock key after the latest successful availability write.
 *
 * @param appliedVersion latest version accepted by the Availability API for this key
 * @param productContext context used for that version (needed to republish the event)
 * @param published      whether the output event for {@code appliedVersion} was published
 */
public record StockState(long appliedVersion, int availableQuantity, ProductContextSummary productContext,
                         boolean published) {

    public StockState markPublished() {
        return new StockState(appliedVersion, availableQuantity, productContext, true);
    }
}
