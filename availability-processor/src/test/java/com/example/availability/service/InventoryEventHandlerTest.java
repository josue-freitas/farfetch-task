package com.example.availability.service;

import com.example.availability.dto.request.InventoryEventMessage;
import com.example.availability.exception.InvalidInputException;
import com.example.availability.model.ProcessingResult.Outcome;
import com.example.availability.repository.InMemoryStockStateRepository;
import com.example.availability.support.FakeProductContextProvider;
import com.example.availability.support.RecordingAvailabilityGateway;
import com.example.availability.support.RecordingEventPublisher;
import com.example.availability.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryEventHandlerTest {

    private FakeProductContextProvider contexts;
    private RecordingAvailabilityGateway gateway;
    private InventoryEventHandler handler;

    @BeforeEach
    void setUp() {
        contexts = new FakeProductContextProvider()
                .with(TestData.context("style-123", "size-38", "PT", "ACTIVE", true));
        gateway = new RecordingAvailabilityGateway();
        AvailabilityProcessor processor = new AvailabilityProcessor(new InMemoryStockStateRepository(),
                gateway, new RecordingEventPublisher(), TestData.noWaitRetries(3), Clock.systemUTC());
        handler = new InventoryEventHandler(processor, contexts, TestData.noWaitRetries(3));
    }

    @Test
    void eventsUseOneSingleItemContextLookupAndNeverTheSnapshot() {
        assertEquals(Outcome.APPLIED, handler.handle(sampleEvent()).outcome());
        assertEquals(1, contexts.singleLookups().size());
        assertEquals(0, contexts.snapshotRequests().size());
    }

    @Test
    void invalidEventsNeverReachTheDependencies() {
        assertThrows(InvalidInputException.class, () -> handler.handle(null));
        assertTrue(contexts.singleLookups().isEmpty());
        assertTrue(gateway.calls().isEmpty());
    }

    private static InventoryEventMessage sampleEvent() {
        return new InventoryEventMessage("msg-0001", "SellerInventoryChanged", 1, "2026-10-20T08:00:00Z",
                "boutique-42", "style-123", "size-38", "PT", 3, 7L);
    }
}
