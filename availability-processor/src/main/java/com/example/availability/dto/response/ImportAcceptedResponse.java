package com.example.availability.dto.response;

/**
 * Body of the {@code 202 Accepted} answer to a CSV upload.
 */
public record ImportAcceptedResponse(String jobId, String state, int totalRows, String statusUrl) {
}
