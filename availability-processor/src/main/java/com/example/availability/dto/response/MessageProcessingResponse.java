package com.example.availability.dto.response;

/**
 * Body returned by {@code POST /_dev/messages}.
 */
public record MessageProcessingResponse(
        String eventId,
        String outcome,
        String reason,
        String message,
        String outputEventId) {
}
