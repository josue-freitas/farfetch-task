package com.example.availability.controller;

import com.example.availability.model.StockKey;
import com.example.availability.support.InjectWireMock;
import com.example.availability.support.WireMockResource;
import com.example.availability.util.OutputEventIds;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * End-to-end tests of the single-event flow against an embedded WireMock that uses the provided
 * mappings. Each test uses its own sellerId so that the in-memory version store does not leak state
 * between tests.
 */
@QuarkusTest
@QuarkusTestResource(WireMockResource.class)
class DevMessagesControllerTest {

    private static final String EVENTS = "/mock-broker/v1/events";

    @InjectWireMock
    WireMockServer wireMock;

    @BeforeEach
    void resetJournal() {
        wireMock.resetRequests();
    }

    @Test
    void sampleEventIsEnrichedWrittenAndPublishedThenReplayIsIgnored() throws IOException {
        String sample = Files.readString(WireMockResource.root().resolve("__files/sample-event.json"));

        given().contentType(ContentType.JSON).body(sample)
                .when().post("/_dev/messages")
                .then().statusCode(200)
                .body("eventId", is("msg-0001"))
                .body("outcome", is("APPLIED"))
                .body("outputEventId", notNullValue());

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/v1/products/style-123/sizes/size-38"))
                .withQueryParam("countryCode", equalTo("PT")));
        wireMock.verify(1, putRequestedFor(urlPathEqualTo("/v1/availability/boutique-42/style-123/size-38"))
                .withQueryParam("countryCode", equalTo("PT"))
                .withRequestBody(matchingJsonPath("$.availableQuantity", equalTo("3")))
                .withRequestBody(matchingJsonPath("$.sourceVersion", equalTo("7")))
                .withRequestBody(matchingJsonPath("$.productContext.productName", equalTo("Linen Shirt 123")))
                .withRequestBody(matchingJsonPath("$.productContext.sizeLabel", equalTo("EU 38"))));
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(EVENTS))
                .withRequestBody(matchingJsonPath("$.eventType", equalTo("SizeAvailabilityUpdated")))
                .withRequestBody(matchingJsonPath("$.sellerId", equalTo("boutique-42")))
                .withRequestBody(matchingJsonPath("$.productId", equalTo("style-123")))
                .withRequestBody(matchingJsonPath("$.sizeId", equalTo("size-38")))
                .withRequestBody(matchingJsonPath("$.countryCode", equalTo("PT")))
                .withRequestBody(matchingJsonPath("$.availableQuantity", equalTo("3")))
                .withRequestBody(matchingJsonPath("$.sourceVersion", equalTo("7")))
                .withRequestBody(matchingJsonPath("$.productContext.brand.name", equalTo("Atelier Example"))));

        // Same business change replayed with a different eventId: nothing is written or published again.
        given().contentType(ContentType.JSON).body(sample.replace("msg-0001", "msg-0001-replay"))
                .when().post("/_dev/messages")
                .then().statusCode(200)
                .body("outcome", is("SKIPPED"))
                .body("reason", is("DUPLICATE_VERSION"));

        wireMock.verify(1, putRequestedFor(urlPathMatching("/v1/availability/boutique-42/.*")));
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(EVENTS)));
    }

    @Test
    void olderVersionArrivingLaterIsSkippedAsStale() {
        String seller = uniqueSeller();
        send(event(seller, "style-123", "size-38", 3, 8)).statusCode(200).body("outcome", is("APPLIED"));

        send(event(seller, "style-123", "size-38", 10, 7)).statusCode(200)
                .body("outcome", is("SKIPPED"))
                .body("reason", is("STALE_VERSION"));

        wireMock.verify(1, putRequestedFor(urlPathEqualTo("/v1/availability/" + seller + "/style-123/size-38")));
        wireMock.verify(0, putRequestedFor(urlPathMatching("/v1/availability/" + seller + "/.*"))
                .withRequestBody(matchingJsonPath("$.sourceVersion", equalTo("7"))));
    }

    @Test
    void unknownProductIsRejected() {
        send(event(uniqueSeller(), "style-50000", "size-38", 1, 1)).statusCode(422)
                .body("outcome", is("REJECTED"))
                .body("reason", is("PRODUCT_CONTEXT_NOT_FOUND"));

        wireMock.verify(0, putRequestedFor(urlPathMatching("/v1/availability/.*")));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo(EVENTS)));
    }

    @Test
    void inactiveProductIsRejected() {
        send(event(uniqueSeller(), "style-9999", "size-38", 1, 1)).statusCode(422)
                .body("reason", is("PRODUCT_INACTIVE"));

        wireMock.verify(0, putRequestedFor(urlPathMatching("/v1/availability/.*")));
    }

    @Test
    void productNotEnabledInCountryIsRejected() {
        send(event(uniqueSeller(), "style-9998", "size-38", 1, 1)).statusCode(422)
                .body("reason", is("PRODUCT_NOT_ENABLED_IN_COUNTRY"));

        wireMock.verify(0, putRequestedFor(urlPathMatching("/v1/availability/.*")));
    }

    @Test
    void productContext429WithLongRetryAfterIsNotRetriedInProcessAndAsksForRedelivery() {
        send(event(uniqueSeller(), "style-90001", "size-38", 1, 1)).statusCode(503)
                .body("outcome", is("FAILED"))
                .body("reason", is("PRODUCT_CONTEXT_UNAVAILABLE"));

        // Retry-After: 5 is longer than the test max-backoff, so exactly one call is made.
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/v1/products/style-90001/sizes/size-38")));
        wireMock.verify(0, putRequestedFor(urlPathMatching("/v1/availability/.*")));
    }

    @Test
    void productContext503IsRetriedThenReportedAsTransient() {
        send(event(uniqueSeller(), "style-90002", "size-38", 1, 1)).statusCode(503)
                .body("reason", is("PRODUCT_CONTEXT_UNAVAILABLE"));

        wireMock.verify(3, getRequestedFor(urlPathEqualTo("/v1/products/style-90002/sizes/size-38")));
    }

    @Test
    void availability503IsRetriedAndNoEventIsPublished() {
        send(event("seller-503", "style-123", "size-38", 1, 1)).statusCode(503)
                .body("reason", is("AVAILABILITY_UPDATE_FAILED"));

        wireMock.verify(3, putRequestedFor(urlPathEqualTo("/v1/availability/seller-503/style-123/size-38")));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo(EVENTS)));
    }

    @Test
    void availability429WithLongRetryAfterIsReportedAsTransient() {
        send(event("seller-429", "style-123", "size-38", 1, 1)).statusCode(503)
                .body("reason", is("AVAILABILITY_UPDATE_FAILED"));

        wireMock.verify(1, putRequestedFor(urlPathEqualTo("/v1/availability/seller-429/style-123/size-38")));
        wireMock.verify(0, postRequestedFor(urlPathEqualTo(EVENTS)));
    }

    @Test
    void publishFailureIsRecoveredByRedeliveryWithoutASecondAvailabilityWrite() {
        String seller = uniqueSeller();
        String expectedEventId = OutputEventIds.forStockVersion(new StockKey(seller, "style-123", "size-40", "PT"), 4);
        StubMapping sinkDown = wireMock.stubFor(post(urlPathEqualTo(EVENTS)).atPriority(1)
                .withRequestBody(matchingJsonPath("$.sellerId", equalTo(seller)))
                .willReturn(aResponse().withStatus(503)));

        send(event(seller, "style-123", "size-40", 5, 4)).statusCode(503)
                .body("reason", is("EVENT_PUBLISH_FAILED"))
                .body("outputEventId", is(expectedEventId));
        wireMock.verify(1, putRequestedFor(urlPathMatching("/v1/availability/" + seller + "/.*")));
        wireMock.verify(3, postRequestedFor(urlPathEqualTo(EVENTS)));

        wireMock.removeStub(sinkDown);
        // The broker redelivers the same business change (possibly with a new delivery id).
        send(event(seller, "style-123", "size-40", 5, 4)).statusCode(200)
                .body("outcome", is("REPUBLISHED"));

        wireMock.verify(1, putRequestedFor(urlPathMatching("/v1/availability/" + seller + "/.*")));
        wireMock.verify(4, postRequestedFor(urlPathEqualTo(EVENTS))
                .withRequestBody(matchingJsonPath("$.eventId", equalTo(expectedEventId))));
    }

    @Test
    void invalidEventIsRejectedWithAllErrors() {
        String invalid = """
                {"eventId":"msg-x","eventType":"Other","schemaVersion":1,"occurredAt":"2026-10-20T08:00:00Z",
                 "sellerId":"s","productId":"style-1","sizeId":"size-34","countryCode":"Portugal",
                 "availableQuantity":-1,"sourceVersion":1}
                """;

        given().contentType(ContentType.JSON).body(invalid)
                .when().post("/_dev/messages")
                .then().statusCode(400)
                .body("error", is("INVALID_INPUT"))
                .body("details", hasItem("eventType must be SellerInventoryChanged"))
                .body("details", hasItem("availableQuantity must be >= 0"));

        wireMock.verify(0, getRequestedFor(urlPathMatching("/v1/products/.*")));
    }

    private static io.restassured.response.ValidatableResponse send(String body) {
        return given().contentType(ContentType.JSON).body(body).when().post("/_dev/messages").then();
    }

    private static String uniqueSeller() {
        return "it-seller-" + UUID.randomUUID();
    }

    static String event(String sellerId, String productId, String sizeId, int quantity, long version) {
        return """
                {"eventId":"%s","eventType":"SellerInventoryChanged","schemaVersion":1,
                 "occurredAt":"2026-10-20T08:00:00Z","sellerId":"%s","productId":"%s","sizeId":"%s",
                 "countryCode":"PT","availableQuantity":%d,"sourceVersion":%d}
                """.formatted(UUID.randomUUID(), sellerId, productId, sizeId, quantity, version);
    }
}
