package com.example.availability.support;

import com.example.availability.dto.external.SizeAvailabilityUpdated;
import com.example.availability.gateway.EventPublisher;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every publish attempt; can be switched to always fail.
 */
public class RecordingEventPublisher implements EventPublisher {

    private final List<SizeAvailabilityUpdated> attempts = new CopyOnWriteArrayList<>();
    private final List<SizeAvailabilityUpdated> published = new CopyOnWriteArrayList<>();
    private volatile RuntimeException failure;

    public void failAlwaysWith(RuntimeException failure) {
        this.failure = failure;
    }

    public void recover() {
        this.failure = null;
    }

    @Override
    public void publish(SizeAvailabilityUpdated event) {
        attempts.add(event);
        RuntimeException current = failure;
        if (current != null) {
            throw current;
        }
        published.add(event);
    }

    public List<SizeAvailabilityUpdated> attempts() {
        return attempts;
    }

    public List<SizeAvailabilityUpdated> published() {
        return published;
    }
}
