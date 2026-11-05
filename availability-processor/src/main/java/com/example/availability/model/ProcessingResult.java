package com.example.availability.model;

/**
 * Result of processing one stock update. It is input-agnostic: the event endpoint turns it into an
 * HTTP status (ack / nack semantics), the bulk worker turns it into job counters.
 *
 * @param reason        set for every outcome except APPLIED and REPUBLISHED
 * @param outputEventId id of the published (or pending) output event, when there is one
 */
public record ProcessingResult(Outcome outcome, ReasonCode reason, String message, String outputEventId) {

    public enum Outcome {
        /**
         * Availability written and output event published.
         */
        APPLIED,
        /**
         * Version had been applied before but its event was not published; the event is now published.
         */
        REPUBLISHED,
        /**
         * Nothing to do: stale or duplicate version. Safe to acknowledge.
         */
        SKIPPED,
        /**
         * Permanent business/data problem; retrying the same input will not help.
         */
        REJECTED,
        /**
         * Transient dependency problem; the same input may succeed later.
         */
        FAILED
    }

    public static ProcessingResult applied(String outputEventId) {
        return new ProcessingResult(Outcome.APPLIED, null, "availability updated and event published", outputEventId);
    }

    public static ProcessingResult republished(String outputEventId) {
        return new ProcessingResult(Outcome.REPUBLISHED, null,
                "version was already applied; pending output event published", outputEventId);
    }

    public static ProcessingResult skipped(ReasonCode reason, String message) {
        return new ProcessingResult(Outcome.SKIPPED, reason, message, null);
    }

    public static ProcessingResult rejected(ReasonCode reason, String message) {
        return new ProcessingResult(Outcome.REJECTED, reason, message, null);
    }

    public static ProcessingResult failed(ReasonCode reason, String message) {
        return new ProcessingResult(Outcome.FAILED, reason, message, null);
    }

    public static ProcessingResult failed(ReasonCode reason, String message, String outputEventId) {
        return new ProcessingResult(Outcome.FAILED, reason, message, outputEventId);
    }

    public boolean isSuccess() {
        return outcome == Outcome.APPLIED || outcome == Outcome.REPUBLISHED;
    }
}
