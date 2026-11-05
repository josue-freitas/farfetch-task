package com.example.availability.controller;

import com.example.availability.support.InjectWireMock;
import com.example.availability.support.WireMockResource;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end tests of the asynchronous CSV import against the provided WireMock mappings.
 */
@QuarkusTest
@QuarkusTestResource(WireMockResource.class)
class AvailabilityImportControllerTest {

    private static final String HEADER = "sellerId,productId,sizeId,countryCode,availableQuantity,sourceVersion\n";
    private static final String IMPORTS = "/v1/availability/imports";

    @InjectWireMock
    WireMockServer wireMock;

    @BeforeEach
    void resetJournal() {
        wireMock.resetRequests();
    }

    @Test
    void rejectsUploadWithMissingHeadersSynchronously() {
        uploadCsv("sellerId,productId\nseller-001,style-1\n")
                .when().post(IMPORTS)
                .then().statusCode(400)
                .body("error", is("INVALID_INPUT"))
                .body("details[0]", containsString("missing required CSV column(s)"));
    }

    @Test
    void rejectsEmptyUploadAndMissingMultipartField() {
        uploadCsv("").when().post(IMPORTS).then().statusCode(400);
        given().multiPart("other", "x").when().post(IMPORTS).then().statusCode(400);
    }

    @Test
    void acceptsOnlyMultipartUploads() {
        given().contentType("text/csv").body(HEADER + "seller-x,style-1,size-34,PT,1,1\n")
                .when().post(IMPORTS)
                .then().statusCode(415);
    }

    @Test
    void unknownJobIs404() {
        given().when().get(IMPORTS + "/does-not-exist").then().statusCode(404)
                .body("error", is("JOB_NOT_FOUND"));
    }

    @Test
    void importsTheProvidedFixtureWithOneSnapshotRequestAndNoSingleLookups() {
        File csv = WireMockResource.root().resolve("__files/bulk-stock.csv").toFile();

        String jobId = given().multiPart("file", csv, "text/csv")
                .when().post(IMPORTS)
                .then().statusCode(202)
                .header("Location", containsString(IMPORTS + "/"))
                .body("jobId", notNullValue())
                .body("totalRows", is(10000))
                .extract().path("jobId");

        JsonPath status = awaitFinished(jobId, Duration.ofMinutes(3));

        assertEquals("COMPLETED", status.getString("state"));
        assertEquals(10000, status.getInt("totalRows"));
        assertEquals(10000, status.getInt("processedRows"));
        assertEquals(10000, status.getInt("succeededRows"));
        assertEquals(0, status.getInt("skippedRows"));
        assertEquals(0, status.getInt("failedRows"));

        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/v1/product-contexts/snapshot"))
                .withQueryParam("countryCode", equalTo("PT")));
        wireMock.verify(0, getRequestedFor(urlPathMatching("/v1/products/.*")));
        assertEquals(10000, wireMock.findAll(putRequestedFor(urlPathMatching("/v1/availability/.*"))).size());
        assertEquals(10000, wireMock.findAll(postRequestedFor(urlPathEqualTo("/mock-broker/v1/events"))).size());
    }

    @Test
    void staleRowsAreSkippedAndBadRowsAreReportedWithoutRejectingTheUpload() {
        String seller = "it-seller-" + UUID.randomUUID();
        // An inventory event already applied version 8 with quantity 3.
        given().contentType(ContentType.JSON)
                .body(DevMessagesControllerTest.event(seller, "style-123", "size-38", 3, 8))
                .when().post("/_dev/messages").then().statusCode(200);
        wireMock.resetRequests();

        String csv = HEADER
                + seller + ",style-123,size-38,PT,10,7\n"    // row 2: stale -> skipped
                + seller + ",style-123,size-40,PT,4,1\n"     // row 3: applied
                + seller + ",style-50000,size-38,PT,1,1\n"   // row 4: unknown product
                + seller + ",style-9999,size-38,PT,1,1\n"    // row 5: inactive
                + seller + ",style-9998,size-38,PT,1,1\n"    // row 6: not enabled in PT
                + seller + ",style-1,size-34,PT,abc,1\n";    // row 7: invalid quantity

        String jobId = uploadCsv(csv)
                .when().post(IMPORTS)
                .then().statusCode(202)
                .extract().path("jobId");

        JsonPath status = awaitFinished(jobId, Duration.ofSeconds(60));

        assertEquals("COMPLETED_WITH_ERRORS", status.getString("state"));
        assertEquals(6, status.getInt("processedRows"));
        assertEquals(1, status.getInt("succeededRows"));
        assertEquals(1, status.getInt("skippedRows"));
        assertEquals(4, status.getInt("failedRows"));
        assertEquals(2, status.getInt("skipped[0].rowNumber"));
        assertEquals("STALE_VERSION", status.getString("skipped[0].code"));
        assertEquals(List.of("PRODUCT_CONTEXT_NOT_FOUND", "PRODUCT_INACTIVE", "PRODUCT_NOT_ENABLED_IN_COUNTRY",
                "INVALID_INPUT"), status.getList("errors.code"));
        assertEquals(List.of(4, 5, 6, 7), status.getList("errors.rowNumber"));

        // The stale row must not have touched the stock written by the event.
        wireMock.verify(0, putRequestedFor(urlPathEqualTo("/v1/availability/" + seller + "/style-123/size-38")));
        wireMock.verify(1, putRequestedFor(urlPathMatching("/v1/availability/" + seller + "/.*")));
    }

    @Test
    void reportsProgressFieldsOfAFinishedJob() {
        String seller = "it-seller-" + UUID.randomUUID();
        String jobId = uploadCsv(HEADER + seller + ",style-2,size-36,PT,1,1\n")
                .when().post(IMPORTS)
                .then().statusCode(202)
                .extract().path("jobId");

        awaitFinished(jobId, Duration.ofSeconds(60));

        given().when().get(IMPORTS + "/" + jobId)
                .then().statusCode(200)
                .body("state", is("COMPLETED"))
                .body("createdAt", notNullValue())
                .body("completedAt", notNullValue())
                .body("errors", empty())
                .body("succeededRows", is(1));
    }

    private static RequestSpecification uploadCsv(String csv) {
        return given().multiPart("file", "import.csv", csv.getBytes(StandardCharsets.UTF_8), "text/csv");
    }

    private static JsonPath awaitFinished(String jobId, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(200)).until(() -> {
            String state = given().when().get(IMPORTS + "/" + jobId).then().statusCode(200)
                    .extract().path("state");
            return List.of("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED").contains(state);
        });
        return given().when().get(IMPORTS + "/" + jobId).then().extract().jsonPath();
    }
}
