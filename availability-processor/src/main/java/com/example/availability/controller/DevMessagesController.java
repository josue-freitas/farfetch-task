package com.example.availability.controller;

import com.example.availability.dto.request.InventoryEventMessage;
import com.example.availability.mapper.ProcessingResultMapper;
import com.example.availability.model.ProcessingResult;
import com.example.availability.service.InventoryEventHandler;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Test-only ingress that simulates a broker delivering one {@code SellerInventoryChanged} message.
 * It calls the same {@link InventoryEventHandler} a production consumer would call; the HTTP status
 * mirrors broker ack / dead-letter / nack semantics (see {@link ProcessingResultMapper}).
 */
@Path("/_dev/messages")
public class DevMessagesController {

    private final InventoryEventHandler handler;

    @Inject
    public DevMessagesController(InventoryEventHandler handler) {
        this.handler = handler;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response receive(InventoryEventMessage message) {
        ProcessingResult result = handler.handle(message);
        return Response.status(ProcessingResultMapper.toHttpStatus(result))
                .entity(ProcessingResultMapper.toResponse(message.eventId(), result))
                .build();
    }
}
