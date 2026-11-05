package com.example.availability.mapper;

import com.example.availability.dto.response.MessageProcessingResponse;
import com.example.availability.model.ProcessingResult;

/**
 * Maps a {@link ProcessingResult} to the HTTP answer of {@code POST /_dev/messages}, using broker
 * semantics:
 * <ul>
 *     <li>200: acknowledged (APPLIED, REPUBLISHED, SKIPPED);</li>
 *     <li>422: permanent business rejection (would go to a dead-letter queue);</li>
 *     <li>503: transient failure, redeliver later (would be a nack).</li>
 * </ul>
 * Validation errors (400) are handled by {@link InvalidInputExceptionMapper}.
 */
public final class ProcessingResultMapper {

    private ProcessingResultMapper() {
    }

    public static int toHttpStatus(ProcessingResult result) {
        return switch (result.outcome()) {
            case APPLIED, REPUBLISHED, SKIPPED -> 200;
            case REJECTED -> 422;
            case FAILED -> 503;
        };
    }

    public static MessageProcessingResponse toResponse(String eventId, ProcessingResult result) {
        return new MessageProcessingResponse(
                eventId,
                result.outcome().name(),
                result.reason() == null ? null : result.reason().name(),
                result.message(),
                result.outputEventId());
    }
}
