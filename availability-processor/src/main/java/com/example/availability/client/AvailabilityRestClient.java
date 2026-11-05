package com.example.availability.client;

import com.example.availability.dto.external.AvailabilityUpdateRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Declarative HTTP client for the Availability API Gateway.
 */
@RegisterRestClient(configKey = "availability-api")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface AvailabilityRestClient {

    @PUT
    @Path("/v1/availability/{sellerId}/{productId}/{sizeId}")
    Response updateAvailability(@PathParam("sellerId") String sellerId,
                                @PathParam("productId") String productId,
                                @PathParam("sizeId") String sizeId,
                                @QueryParam("countryCode") String countryCode,
                                AvailabilityUpdateRequest body);
}
