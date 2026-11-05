package com.example.availability.mapper;

import com.example.availability.dto.response.ImportAcceptedResponse;
import com.example.availability.dto.response.ImportJobStatusResponse;
import com.example.availability.model.ImportJob;

/**
 * Maps an {@link ImportJob} to the bodies returned by the import endpoints.
 */
public final class ImportJobMapper {

    private ImportJobMapper() {
    }

    public static ImportAcceptedResponse toAcceptedResponse(ImportJob job, String statusUrl) {
        return new ImportAcceptedResponse(job.id(), job.state().name(), job.totalRows(), statusUrl);
    }

    public static ImportJobStatusResponse toStatusResponse(ImportJob job) {
        return new ImportJobStatusResponse(
                job.id(),
                job.state().name(),
                job.createdAt(),
                job.startedAt(),
                job.completedAt(),
                job.totalRows(),
                job.processedRows(),
                job.succeededRows(),
                job.skippedRows(),
                job.failedRows(),
                job.failureMessage(),
                job.errors(),
                job.skipped());
    }
}
