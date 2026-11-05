package com.example.availability.service;

import com.example.availability.dto.external.SizeAvailabilityUpdated;
import com.example.availability.exception.PermanentDependencyException;
import com.example.availability.exception.ProductContextNotFoundException;
import com.example.availability.exception.TransientDependencyException;
import com.example.availability.model.ProcessingResult;
import com.example.availability.model.ProcessingResult.Outcome;
import com.example.availability.model.ProductContext;
import com.example.availability.model.ReasonCode;
import com.example.availability.model.StockKey;
import com.example.availability.repository.InMemoryStockStateRepository;
import com.example.availability.support.RecordingAvailabilityGateway;
import com.example.availability.support.RecordingEventPublisher;
import com.example.availability.support.TestData;
import com.example.availability.util.OutputEventIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.example.availability.support.TestData.update;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvailabilityProcessorTest {

    private static final Instant NOW = Instant.parse("2026-10-20T08:01:00Z");
    private static final ContextResolver ACTIVE = TestData::activeContext;

    private RecordingAvailabilityGateway gateway;
    private RecordingEventPublisher publisher;
    private InMemoryStockStateRepository stockStates;
    private AvailabilityProcessor processor;

    @BeforeEach
    void setUp() {
        gateway = new RecordingAvailabilityGateway();
        publisher = new RecordingEventPublisher();
        stockStates = new InMemoryStockStateRepository();
        processor = new AvailabilityProcessor(stockStates, gateway, publisher, TestData.noWaitRetries(3),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void appliesNewUpdateWritesAvailabilityAndPublishesEnrichedEvent() {
        ProcessingResult result = processor.process(update("boutique-42", "style-123", "size-38", 3, 7), ACTIVE);

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(1, gateway.calls().size());
        assertEquals(3, gateway.calls().get(0).request().availableQuantity());
        assertEquals(7L, gateway.calls().get(0).request().sourceVersion());
        assertEquals("Linen Shirt 123", gateway.calls().get(0).request().productContext().productName());

        assertEquals(1, publisher.published().size());
        SizeAvailabilityUpdated event = publisher.published().get(0);
        assertEquals("SizeAvailabilityUpdated", event.eventType());
        assertEquals("boutique-42", event.sellerId());
        assertEquals("style-123", event.productId());
        assertEquals("size-38", event.sizeId());
        assertEquals("PT", event.countryCode());
        assertEquals(3, event.availableQuantity());
        assertEquals(7L, event.sourceVersion());
        assertEquals("EU 38", event.productContext().sizeLabel());
        assertEquals(NOW, event.occurredAt());
        assertEquals(result.outputEventId(), event.eventId());
    }

    @Test
    void sameVersionDeliveredAgainIsSkippedAsDuplicate() {
        processor.process(update("boutique-42", "style-123", "size-38", 3, 7), ACTIVE);

        ProcessingResult replay = processor.process(update("boutique-42", "style-123", "size-38", 3, 7), ACTIVE);

        assertEquals(Outcome.SKIPPED, replay.outcome());
        assertEquals(ReasonCode.DUPLICATE_VERSION, replay.reason());
        assertEquals(1, gateway.calls().size());
        assertEquals(1, publisher.attempts().size());
    }

    @Test
    void olderVersionIsSkippedAsStaleAndLeavesStockUntouched() {
        StockKey key = new StockKey("boutique-42", "style-123", "size-38", "PT");
        processor.process(update("boutique-42", "style-123", "size-38", 3, 8), ACTIVE);

        ProcessingResult stale = processor.process(update("boutique-42", "style-123", "size-38", 10, 7), ACTIVE);

        assertEquals(Outcome.SKIPPED, stale.outcome());
        assertEquals(ReasonCode.STALE_VERSION, stale.reason());
        assertEquals(1, gateway.calls().size());
        assertEquals(1, publisher.attempts().size());
        assertEquals(8L, stockStates.find(key).orElseThrow().appliedVersion());
        assertEquals(3, stockStates.find(key).orElseThrow().availableQuantity());
    }

    @Test
    void versionsOfDifferentStockKeysAreNeverCompared() {
        processor.process(update("seller-A", "style-123", "size-38", 3, 8), ACTIVE);

        assertEquals(Outcome.APPLIED, processor.process(update("seller-B", "style-123", "size-38", 1, 7), ACTIVE).outcome());
        assertEquals(Outcome.APPLIED, processor.process(update("seller-A", "style-124", "size-38", 1, 7), ACTIVE).outcome());
        assertEquals(Outcome.APPLIED, processor.process(update("seller-A", "style-123", "size-40", 1, 7), ACTIVE).outcome());
        assertEquals(4, gateway.calls().size());
    }

    @Test
    void newerVersionIsAppliedAfterOlderOne() {
        processor.process(update("boutique-42", "style-123", "size-38", 3, 7), ACTIVE);

        ProcessingResult newer = processor.process(update("boutique-42", "style-123", "size-38", 0, 8), ACTIVE);

        assertEquals(Outcome.APPLIED, newer.outcome());
        assertEquals(2, publisher.published().size());
    }

    @Test
    void unknownProductIsRejectedWithoutWriting() {
        ContextResolver notFound = key -> {
            throw new ProductContextNotFoundException("test", key);
        };

        ProcessingResult result = processor.process(update("s", "style-50000", "size-38", 1, 1), notFound);

        assertEquals(Outcome.REJECTED, result.outcome());
        assertEquals(ReasonCode.PRODUCT_CONTEXT_NOT_FOUND, result.reason());
        assertTrue(gateway.calls().isEmpty());
        assertTrue(publisher.attempts().isEmpty());
    }

    @Test
    void inactiveProductIsRejectedWithoutWriting() {
        ContextResolver inactive = key -> TestData.context(key.productId(), key.sizeId(), "PT", "INACTIVE", true);

        ProcessingResult result = processor.process(update("s", "style-9999", "size-38", 1, 1), inactive);

        assertEquals(Outcome.REJECTED, result.outcome());
        assertEquals(ReasonCode.PRODUCT_INACTIVE, result.reason());
        assertTrue(gateway.calls().isEmpty());
    }

    @Test
    void productNotEnabledInCountryIsRejectedWithoutWriting() {
        ContextResolver disabled = key -> TestData.context(key.productId(), key.sizeId(), "PT", ProductContext.ACTIVE, false);

        ProcessingResult result = processor.process(update("s", "style-9998", "size-38", 1, 1), disabled);

        assertEquals(Outcome.REJECTED, result.outcome());
        assertEquals(ReasonCode.PRODUCT_NOT_ENABLED_IN_COUNTRY, result.reason());
        assertTrue(gateway.calls().isEmpty());
    }

    @Test
    void unavailableProductContextIsARetryableFailure() {
        ContextResolver unavailable = key -> {
            throw new TransientDependencyException("test", "HTTP 503", null, null);
        };

        ProcessingResult result = processor.process(update("s", "style-90002", "size-38", 1, 1), unavailable);

        assertEquals(Outcome.FAILED, result.outcome());
        assertEquals(ReasonCode.PRODUCT_CONTEXT_UNAVAILABLE, result.reason());
        assertTrue(gateway.calls().isEmpty());
    }

    @Test
    void transientAvailabilityFailuresAreRetried() {
        gateway.failNext(2, new TransientDependencyException("availability-api", "HTTP 503", null, null));

        ProcessingResult result = processor.process(update("s", "style-1", "size-34", 1, 1), ACTIVE);

        assertEquals(Outcome.APPLIED, result.outcome());
        assertEquals(3, gateway.calls().size());
        assertEquals(1, publisher.published().size());
    }

    @Test
    void availabilityFailureAfterRetriesRecordsNothingSoRedeliveryTriesAgain() {
        gateway.failNext(3, new TransientDependencyException("availability-api", "HTTP 503", null, null));
        StockKey key = new StockKey("s", "style-1", "size-34", "PT");

        ProcessingResult first = processor.process(update("s", "style-1", "size-34", 1, 1), ACTIVE);

        assertEquals(Outcome.FAILED, first.outcome());
        assertEquals(ReasonCode.AVAILABILITY_UPDATE_FAILED, first.reason());
        assertTrue(publisher.attempts().isEmpty());
        assertFalse(stockStates.find(key).isPresent());

        ProcessingResult redelivery = processor.process(update("s", "style-1", "size-34", 1, 1), ACTIVE);
        assertEquals(Outcome.APPLIED, redelivery.outcome());
    }

    @Test
    void permanentAvailabilityErrorIsRejectedWithoutRetry() {
        gateway.failNext(1, new PermanentDependencyException("availability-api", 400, "HTTP 400"));

        ProcessingResult result = processor.process(update("s", "style-1", "size-34", 1, 1), ACTIVE);

        assertEquals(Outcome.REJECTED, result.outcome());
        assertEquals(ReasonCode.AVAILABILITY_UPDATE_REJECTED, result.reason());
        assertEquals(1, gateway.calls().size());
    }

    @Test
    void publishFailureKeepsAppliedVersionAndRedeliveryRepublishesWithoutSecondWrite() {
        StockKey key = new StockKey("s", "style-1", "size-34", "PT");
        publisher.failAlwaysWith(new TransientDependencyException("event-sink", "HTTP 503", null, null));

        ProcessingResult first = processor.process(update("s", "style-1", "size-34", 5, 4), ACTIVE);

        assertEquals(Outcome.FAILED, first.outcome());
        assertEquals(ReasonCode.EVENT_PUBLISH_FAILED, first.reason());
        assertEquals(1, gateway.calls().size());
        assertEquals(3, publisher.attempts().size());
        assertEquals(4L, stockStates.find(key).orElseThrow().appliedVersion());
        assertFalse(stockStates.find(key).orElseThrow().published());

        // An older version arriving now must still be treated as stale.
        assertEquals(ReasonCode.STALE_VERSION,
                processor.process(update("s", "style-1", "size-34", 9, 3), ACTIVE).reason());

        publisher.recover();
        ProcessingResult redelivery = processor.process(update("s", "style-1", "size-34", 5, 4), ACTIVE);

        assertEquals(Outcome.REPUBLISHED, redelivery.outcome());
        assertEquals(1, gateway.calls().size());
        assertEquals(1, publisher.published().size());
        assertEquals(first.outputEventId(), publisher.published().get(0).eventId());
        assertEquals(5, publisher.published().get(0).availableQuantity());
        assertTrue(stockStates.find(key).orElseThrow().published());

        ProcessingResult duplicate = processor.process(update("s", "style-1", "size-34", 5, 4), ACTIVE);
        assertEquals(ReasonCode.DUPLICATE_VERSION, duplicate.reason());
    }

    @Test
    void outputEventIdIsDeterministicPerStockKeyAndVersion() {
        StockKey key = new StockKey("s", "style-1", "size-34", "PT");

        assertEquals(OutputEventIds.forStockVersion(key, 4), OutputEventIds.forStockVersion(key, 4));
        assertFalse(OutputEventIds.forStockVersion(key, 4).equals(OutputEventIds.forStockVersion(key, 5)));
    }
}
