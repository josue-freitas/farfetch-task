package com.example.availability.support;

import com.example.availability.dto.external.AvailabilityUpdateRequest;
import com.example.availability.gateway.AvailabilityGateway;
import com.example.availability.model.StockKey;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every availability write; can be scripted to fail the next N calls.
 */
public class RecordingAvailabilityGateway implements AvailabilityGateway {

    public record Call(StockKey key, AvailabilityUpdateRequest request) {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Queue<RuntimeException> scriptedFailures = new ConcurrentLinkedQueue<>();

    public void failNext(int times, RuntimeException failure) {
        for (int i = 0; i < times; i++) {
            scriptedFailures.add(failure);
        }
    }

    @Override
    public void update(StockKey key, AvailabilityUpdateRequest request) {
        calls.add(new Call(key, request));
        RuntimeException failure = scriptedFailures.poll();
        if (failure != null) {
            throw failure;
        }
    }

    public List<Call> calls() {
        return calls;
    }
}
