package com.example.availability.dto.response;

import com.example.availability.model.ImportJob;

import java.time.Instant;
import java.util.List;

/**
 * Body of {@code GET /v1/availability/imports/{jobId}}.
 */
public record ImportJobStatusResponse(
        String jobId,
        String state,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        int totalRows,
        int processedRows,
        int succeededRows,
        int skippedRows,
        int failedRows,
        String failureMessage,
        List<ImportJob.RowIssue> errors,
        List<ImportJob.RowIssue> skipped) {
}
