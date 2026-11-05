package com.example.availability.client;

import com.example.availability.dto.external.SizeAvailabilityUpdated;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Declarative HTTP client for the local output-event sink (stand-in for a real broker).
 */
@RegisterRestClient(configKey = "event-sink")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface EventSinkRestClient {

    @POST
    @Path("/mock-broker/v1/events")
    Response publish(SizeAvailabilityUpdated event);
}
