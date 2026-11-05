package com.example.availability.controller;

import com.example.availability.dto.response.ErrorResponse;
import com.example.availability.exception.InvalidInputException;
import com.example.availability.mapper.ImportJobMapper;
import com.example.availability.model.ImportJob;
import com.example.availability.model.ImportRow;
import com.example.availability.parser.CsvImportParser;
import com.example.availability.repository.ImportJobRepository;
import com.example.availability.service.BulkImportService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * CSV bulk import API. The upload is parsed and checked synchronously (400 on a malformed request or
 * missing headers); rows are processed asynchronously and progress is exposed per job.
 *
 * <p>The CSV is uploaded as {@code multipart/form-data} in the field {@code file} (standard file
 * upload; any other content type is answered with 415 by JAX-RS).
 */
@Path("/v1/availability/imports")
@Produces(MediaType.APPLICATION_JSON)
public class AvailabilityImportController {

    private static final String BASE_PATH = "/v1/availability/imports/";

    private final BulkImportService bulkImportService;
    private final ImportJobRepository jobRepository;

    @Inject
    public AvailabilityImportController(BulkImportService bulkImportService, ImportJobRepository jobRepository) {
        this.bulkImportService = bulkImportService;
        this.jobRepository = jobRepository;
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadFile(@RestForm("file") FileUpload file) {
        if (file == null) {
            throw new InvalidInputException(List.of("multipart field 'file' is required"));
        }
        String content;
        try {
            content = Files.readString(file.uploadedFile(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InvalidInputException(List.of("uploaded file is not readable UTF-8 text"));
        }
        return accept(content);
    }

    @GET
    @Path("/{jobId}")
    public Response status(@PathParam("jobId") String jobId) {
        return jobRepository.find(jobId)
                .map(job -> Response.ok(ImportJobMapper.toStatusResponse(job)).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND)
                        .entity(new ErrorResponse("JOB_NOT_FOUND", List.of("no import job with id " + jobId)))
                        .build());
    }

    private Response accept(String csv) {
        List<ImportRow> rows = CsvImportParser.parse(csv);
        ImportJob job = bulkImportService.submit(rows);
        String statusUrl = BASE_PATH + job.id();
        return Response.accepted(ImportJobMapper.toAcceptedResponse(job, statusUrl))
                .location(URI.create(statusUrl))
                .build();
    }
}
