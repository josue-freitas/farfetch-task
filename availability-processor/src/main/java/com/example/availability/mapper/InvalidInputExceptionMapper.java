package com.example.availability.mapper;

import com.example.availability.dto.response.ErrorResponse;
import com.example.availability.exception.InvalidInputException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Maps validation failures of events and CSV uploads to {@code 400 Bad Request}.
 */
public class InvalidInputExceptionMapper {

    @ServerExceptionMapper
    public RestResponse<ErrorResponse> invalidInput(InvalidInputException e) {
        return RestResponse.status(Response.Status.BAD_REQUEST, new ErrorResponse("INVALID_INPUT", e.errors()));
    }
}
