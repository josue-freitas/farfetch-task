package com.example.availability.model;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Progress of one asynchronous CSV import. Counters are updated concurrently by the row workers and
 * read by {@code GET /v1/availability/imports/{jobId}}.
 *
 * <p>Invariant once finished: {@code processed = succeeded + skipped + failed = total}.
 */
public final class ImportJob {

    public enum State {
        /**
         * Accepted, waiting for a free job worker.
         */
        QUEUED,
        /**
         * Rows are being processed.
         */
        RUNNING,
        /**
         * Every row was applied or skipped (stale/duplicate).
         */
        COMPLETED,
        /**
         * Finished, but at least one row failed; see the row-level errors.
         */
        COMPLETED_WITH_ERRORS,
        /**
         * The job itself crashed (unexpected error); counters show how far it got.
         */
        FAILED
    }

    /**
     * Row-level result for a row that was not applied.
     */
    public record RowIssue(int rowNumber, String sellerId, String productId, String sizeId, String countryCode,
                           String sourceVersion, String code, String message) {
    }

    private final String id;
    private final int totalRows;
    private final Instant createdAt;
    private volatile State state = State.QUEUED;
    private volatile Instant startedAt;
    private volatile Instant completedAt;
    private volatile String failureMessage;

    private final AtomicInteger processed = new AtomicInteger();
    private final AtomicInteger succeeded = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final Queue<RowIssue> errors = new ConcurrentLinkedQueue<>();
    private final Queue<RowIssue> skippedRows = new ConcurrentLinkedQueue<>();

    public ImportJob(String id, int totalRows, Instant createdAt) {
        this.id = id;
        this.totalRows = totalRows;
        this.createdAt = createdAt;
    }

    public void markRunning(Instant now) {
        startedAt = now;
        state = State.RUNNING;
    }

    public void recordSuccess() {
        succeeded.incrementAndGet();
        processed.incrementAndGet();
    }

    public void recordSkipped(RowIssue issue) {
        skippedRows.add(issue);
        skipped.incrementAndGet();
        processed.incrementAndGet();
    }

    public void recordFailure(RowIssue issue) {
        errors.add(issue);
        failed.incrementAndGet();
        processed.incrementAndGet();
    }

    public void complete(Instant now) {
        completedAt = now;
        state = failed.get() == 0 ? State.COMPLETED : State.COMPLETED_WITH_ERRORS;
    }

    public void fail(Instant now, String message) {
        failureMessage = message;
        completedAt = now;
        state = State.FAILED;
    }

    public String id() {
        return id;
    }

    public State state() {
        return state;
    }

    public boolean isFinished() {
        return state == State.COMPLETED || state == State.COMPLETED_WITH_ERRORS || state == State.FAILED;
    }

    public int totalRows() {
        return totalRows;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public String failureMessage() {
        return failureMessage;
    }

    public int processedRows() {
        return processed.get();
    }

    public int succeededRows() {
        return succeeded.get();
    }

    public int skippedRows() {
        return skipped.get();
    }

    public int failedRows() {
        return failed.get();
    }

    /**
     * Failed rows, sorted by row number.
     */
    public List<RowIssue> errors() {
        return sorted(errors);
    }

    /**
     * Skipped (stale / duplicate) rows, sorted by row number.
     */
    public List<RowIssue> skipped() {
        return sorted(skippedRows);
    }

    private static List<RowIssue> sorted(Queue<RowIssue> issues) {
        return issues.stream().sorted(Comparator.comparingInt(RowIssue::rowNumber)).toList();
    }
}
