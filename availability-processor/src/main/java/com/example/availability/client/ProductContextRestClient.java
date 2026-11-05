package com.example.availability.client;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Declarative HTTP client for the Product Context API (WireMock locally). Base URL:
 * {@code quarkus.rest-client.product-context.url}. Methods return the raw {@link Response} so the
 * adapter can classify status codes and read {@code Retry-After}.
 */
@RegisterRestClient(configKey = "product-context")
@Produces(MediaType.APPLICATION_JSON)
public interface ProductContextRestClient {

    @GET
    @Path("/v1/products/{productId}/sizes/{sizeId}")
    Response getProductContext(@PathParam("productId") String productId,
                               @PathParam("sizeId") String sizeId,
                               @QueryParam("countryCode") String countryCode);

    @GET
    @Path("/v1/product-contexts/snapshot")
    Response getSnapshot(@QueryParam("countryCode") String countryCode);
}
