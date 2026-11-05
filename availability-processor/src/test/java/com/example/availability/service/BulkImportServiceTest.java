package com.example.availability.service;

import com.example.availability.model.ProcessingResult.Outcome;
import com.example.availability.model.ImportJob;
import com.example.availability.parser.CsvImportParser;
import com.example.availability.repository.ImportJobRepository;
import com.example.availability.repository.InMemoryStockStateRepository;
import com.example.availability.support.FakeProductContextProvider;
import com.example.availability.support.RecordingAvailabilityGateway;
import com.example.availability.support.RecordingEventPublisher;
import com.example.availability.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BulkImportServiceTest {

    private static final String HEADER = "sellerId,productId,sizeId,countryCode,availableQuantity,sourceVersion\n";

    private FakeProductContextProvider contexts;
    private RecordingAvailabilityGateway gateway;
    private RecordingEventPublisher publisher;
    private AvailabilityProcessor processor;
    private BulkImportService service;

    @BeforeEach
    void setUp() {
        contexts = new FakeProductContextProvider()
                .with(TestData.context("style-1", "size-34", "PT", "ACTIVE", true))
                .with(TestData.context("style-1", "size-36", "PT", "ACTIVE", true))
                .with(TestData.context("style-123", "size-38", "PT", "ACTIVE", true))
                .with(TestData.context("style-9999", "size-38", "PT", "INACTIVE", true))
                .with(TestData.context("style-7", "size-40", "ES", "ACTIVE", true));
        gateway = new RecordingAvailabilityGateway();
        publisher = new RecordingEventPublisher();
        processor = new AvailabilityProcessor(new InMemoryStockStateRepository(), gateway, publisher,
                TestData.noWaitRetries(3), Clock.systemUTC());
        service = new BulkImportService(processor, contexts, TestData.noWaitRetries(3), new ImportJobRepository(),
                Clock.systemUTC(), 1, 4);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void fetchesOneSnapshotPerCountryAndNeverLooksUpSingleItems() throws InterruptedException {
        ImportJob job = runImport(HEADER
                + "seller-001,style-1,size-34,PT,0,1\n"
                + "seller-002,style-1,size-36,PT,1,1\n"
                + "seller-003,style-7,size-40,ES,2,1\n");

        assertEquals(ImportJob.State.COMPLETED, job.state());
        assertEquals(3, job.succeededRows());
        assertEquals(2, contexts.snapshotRequests().size());
        assertTrue(contexts.snapshotRequests().containsAll(List.of("PT", "ES")));
        assertEquals(0, contexts.singleLookups().size());
        assertEquals(3, gateway.calls().size());
        assertEquals(3, publisher.published().size());
    }

    @Test
    void staleRowAgainstAVersionAppliedByAnEventIsSkippedAndOtherRowsContinue() throws InterruptedException {
        // An inventory event already applied version 8 for this stock key.
        assertEquals(Outcome.APPLIED, processor.process(
                TestData.update("boutique-42", "style-123", "size-38", 3, 8), TestData::activeContext).outcome());
        int writesBefore = gateway.calls().size();

        ImportJob job = runImport(HEADER
                + "boutique-42,style-123,size-38,PT,10,7\n"
                + "seller-001,style-1,size-34,PT,4,1\n");

        assertEquals(ImportJob.State.COMPLETED, job.state());
        assertEquals(2, job.processedRows());
        assertEquals(1, job.succeededRows());
        assertEquals(1, job.skippedRows());
        assertEquals(0, job.failedRows());
        assertEquals(2, job.skipped().get(0).rowNumber());
        assertEquals("STALE_VERSION", job.skipped().get(0).code());
        assertEquals(writesBefore + 1, gateway.calls().size());
    }

    @Test
    void reportsRowLevelFailuresAndKeepsCountsConsistent() throws InterruptedException {
        ImportJob job = runImport(HEADER
                + "seller-001,style-1,size-34,PT,0,1\n"      // row 2: ok
                + "seller-001,style-1,size-34,PT,0,1\n"      // row 3: same version again -> duplicate (skipped)
                + "seller-002,style-50000,size-38,PT,1,1\n"  // row 4: not in snapshot
                + "seller-003,style-9999,size-38,PT,1,1\n"   // row 5: inactive
                + "seller-004,style-1,size-36,PT,abc,1\n"    // row 6: invalid quantity
                + "seller-005,style-1,size-36,PT,1\n");      // row 7: wrong number of fields

        assertEquals(ImportJob.State.COMPLETED_WITH_ERRORS, job.state());
        assertEquals(6, job.totalRows());
        assertEquals(6, job.processedRows());
        assertEquals(1, job.succeededRows());
        assertEquals(1, job.skippedRows());
        assertEquals(4, job.failedRows());
        assertEquals(job.processedRows(), job.succeededRows() + job.skippedRows() + job.failedRows());

        List<String> codes = new ArrayList<>();
        job.errors().forEach(issue -> codes.add(issue.rowNumber() + ":" + issue.code()));
        assertEquals(List.of("4:PRODUCT_CONTEXT_NOT_FOUND", "5:PRODUCT_INACTIVE", "6:INVALID_INPUT", "7:INVALID_INPUT"),
                codes);
    }

    @Test
    void unavailableSnapshotFailsThatCountrysRowsAsRetryable() throws InterruptedException {
        contexts.makeSnapshotUnavailable("ES");

        ImportJob job = runImport(HEADER
                + "seller-001,style-1,size-34,PT,0,1\n"
                + "seller-003,style-7,size-40,ES,2,1\n");

        assertEquals(ImportJob.State.COMPLETED_WITH_ERRORS, job.state());
        assertEquals(1, job.succeededRows());
        assertEquals(1, job.failedRows());
        assertEquals("PRODUCT_CONTEXT_UNAVAILABLE", job.errors().get(0).code());
        assertEquals(3, contexts.snapshotRequests().stream().filter("ES"::equals).count());
    }

    private ImportJob runImport(String csv) throws InterruptedException {
        ImportJob job = service.submit(CsvImportParser.parse(csv));
        long deadline = System.currentTimeMillis() + 10_000;
        while (!job.isFinished()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("import did not finish in time, state=" + job.state());
            }
            Thread.sleep(10);
        }
        return job;
    }
}
