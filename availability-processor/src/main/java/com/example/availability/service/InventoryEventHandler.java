package com.example.availability.service;

import com.example.availability.dto.request.InventoryEventMessage;
import com.example.availability.exception.InvalidInputException;
import com.example.availability.gateway.ProductContextProvider;
import com.example.availability.mapper.StockUpdateMapper;
import com.example.availability.model.ProcessingResult;
import com.example.availability.model.StockUpdate;
import com.example.availability.util.RetryPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Message-consumer logic for {@code SellerInventoryChanged}. It is transport-agnostic: the test-only
 * {@code POST /_dev/messages} endpoint calls it today, and a production broker consumer (e.g. a
 * Kafka listener) would call exactly the same method.
 */
@ApplicationScoped
public class InventoryEventHandler {

    private final AvailabilityProcessor processor;
    private final ProductContextProvider productContexts;
    private final RetryPolicy retryPolicy;

    @Inject
    public InventoryEventHandler(AvailabilityProcessor processor, ProductContextProvider productContexts,
                                 RetryPolicy retryPolicy) {
        this.processor = processor;
        this.productContexts = productContexts;
        this.retryPolicy = retryPolicy;
    }

    /**
     * @throws InvalidInputException when the message does not satisfy the contract
     */
    public ProcessingResult handle(InventoryEventMessage message) {
        StockUpdate update = StockUpdateMapper.fromEvent(message);
        // Events resolve their context with a single HTTP lookup per event, retried on transient errors.
        return processor.process(update, key -> retryPolicy.call(() -> productContexts.findOne(key)));
    }
}
