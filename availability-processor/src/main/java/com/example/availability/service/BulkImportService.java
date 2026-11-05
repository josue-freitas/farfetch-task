package com.example.availability.service;

import com.example.availability.exception.InvalidInputException;
import com.example.availability.exception.PermanentDependencyException;
import com.example.availability.exception.ProductContextNotFoundException;
import com.example.availability.exception.TransientDependencyException;
import com.example.availability.gateway.ProductContextProvider;
import com.example.availability.mapper.StockUpdateMapper;
import com.example.availability.model.ContextKey;
import com.example.availability.model.ImportJob;
import com.example.availability.model.ImportRow;
import com.example.availability.model.ProcessingResult;
import com.example.availability.model.ProductContext;
import com.example.availability.model.ReasonCode;
import com.example.availability.model.StockUpdate;
import com.example.availability.repository.ImportJobRepository;
import com.example.availability.util.RetryPolicy;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous CSV import worker.
 *
 * <p>What differs from the event flow is only how input is accepted and how context is fetched:
 * one snapshot download per distinct country instead of one HTTP lookup per row. Every row then
 * goes through the same {@link AvailabilityProcessor} as events.
 *
 * <p>Flow control: rows of ALL jobs share one fixed-size row pool ({@code bulk.row-concurrency}),
 * so the number of in-flight calls to the Availability API / event sink is bounded no matter how
 * many uploads arrive; jobs themselves run on a small pool ({@code bulk.max-concurrent-jobs}).
 */
@ApplicationScoped
public class BulkImportService {

    private static final Logger LOG = Logger.getLogger(BulkImportService.class);

    private final AvailabilityProcessor processor;
    private final ProductContextProvider productContexts;
    private final RetryPolicy retryPolicy;
    private final ImportJobRepository jobRepository;
    private final Clock clock;
    private final ExecutorService jobExecutor;
    private final ExecutorService rowExecutor;

    @Inject
    public BulkImportService(AvailabilityProcessor processor,
                             ProductContextProvider productContexts,
                             RetryPolicy retryPolicy,
                             ImportJobRepository jobRepository,
                             Clock clock,
                             @ConfigProperty(name = "bulk.max-concurrent-jobs", defaultValue = "2") int maxConcurrentJobs,
                             @ConfigProperty(name = "bulk.row-concurrency", defaultValue = "8") int rowConcurrency) {
        this.processor = processor;
        this.productContexts = productContexts;
        this.retryPolicy = retryPolicy;
        this.jobRepository = jobRepository;
        this.clock = clock;
        this.jobExecutor = Executors.newFixedThreadPool(maxConcurrentJobs, namedThreads("import-job"));
        this.rowExecutor = Executors.newFixedThreadPool(rowConcurrency, namedThreads("import-row"));
    }

    /**
     * Registers the job and schedules it; returns immediately (the caller answers 202).
     */
    public ImportJob submit(List<ImportRow> rows) {
        ImportJob job = new ImportJob(UUID.randomUUID().toString(), rows.size(), clock.instant());
        jobRepository.save(job);
        jobExecutor.execute(() -> run(job, rows));
        return job;
    }

    @PreDestroy
    void shutdown() {
        jobExecutor.shutdownNow();
        rowExecutor.shutdownNow();
    }

    private record PendingRow(ImportRow row, StockUpdate update) {
    }

    void run(ImportJob job, List<ImportRow> rows) {
        job.markRunning(clock.instant());
        LOG.infof("Import %s started with %d rows", job.id(), rows.size());
        try {
            // 1. Row-level validation (same rules as events). Invalid rows fail, the rest continue.
            List<PendingRow> pending = new ArrayList<>(rows.size());
            for (ImportRow row : rows) {
                try {
                    pending.add(new PendingRow(row,
                            StockUpdateMapper.fromRow(row, "import " + job.id() + " row " + row.rowNumber())));
                } catch (InvalidInputException e) {
                    job.recordFailure(issue(row, ReasonCode.INVALID_INPUT, String.join("; ", e.errors())));
                }
            }

            // 2. One snapshot request per distinct country, filtered to the keys of this upload.
            ContextResolver resolver = loadSnapshots(pending);

            // 3. Process rows through the shared flow with bounded parallelism.
            CompletableFuture<?>[] futures = pending.stream()
                    .map(p -> CompletableFuture.runAsync(() -> processRow(job, p, resolver), rowExecutor))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).join();

            job.complete(clock.instant());
            LOG.infof("Import %s finished: %s (succeeded=%d, skipped=%d, failed=%d)", job.id(), job.state(),
                    job.succeededRows(), job.skippedRows(), job.failedRows());
        } catch (RuntimeException e) {
            LOG.errorf(e, "Import %s crashed", job.id());
            job.fail(clock.instant(), e.toString());
        }
    }

    /**
     * Builds an in-memory resolver: O(S) to stream each snapshot (S = snapshot size), O(1) per row
     * lookup, and only contexts referenced by the upload are kept in memory.
     */
    private ContextResolver loadSnapshots(List<PendingRow> pending) {
        Map<String, Set<ContextKey>> wantedByCountry = new HashMap<>();
        for (PendingRow p : pending) {
            ContextKey key = p.update().key().contextKey();
            wantedByCountry.computeIfAbsent(key.countryCode(), c -> new HashSet<>()).add(key);
        }

        Map<ContextKey, ProductContext> contexts = new HashMap<>();
        Map<String, String> unavailableCountries = new HashMap<>();
        wantedByCountry.forEach((country, wanted) -> {
            try {
                contexts.putAll(retryPolicy.call(() -> productContexts.fetchSnapshot(country, wanted)));
            } catch (TransientDependencyException | PermanentDependencyException e) {
                LOG.warnf("Snapshot for %s unavailable: %s", country, e.getMessage());
                unavailableCountries.put(country, e.getMessage());
            }
        });

        Map<ContextKey, ProductContext> index = Map.copyOf(contexts);
        Map<String, String> unavailable = Map.copyOf(unavailableCountries);
        return key -> {
            String reason = unavailable.get(key.countryCode());
            if (reason != null) {
                throw new TransientDependencyException("product-context-snapshot",
                        "snapshot for " + key.countryCode() + " unavailable (" + reason + ")", null, null);
            }
            ProductContext context = index.get(key);
            if (context == null) {
                throw new ProductContextNotFoundException("product-context-snapshot", key);
            }
            return context;
        };
    }

    private void processRow(ImportJob job, PendingRow pending, ContextResolver resolver) {
        ProcessingResult result;
        try {
            result = processor.process(pending.update(), resolver);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Unexpected error on import %s row %d", job.id(), pending.row().rowNumber());
            result = ProcessingResult.failed(ReasonCode.UNEXPECTED_ERROR, e.toString());
        }
        switch (result.outcome()) {
            case APPLIED, REPUBLISHED -> job.recordSuccess();
            case SKIPPED -> job.recordSkipped(issue(pending.row(), result.reason(), result.message()));
            case REJECTED, FAILED -> job.recordFailure(issue(pending.row(), result.reason(), result.message()));
        }
    }

    private static ImportJob.RowIssue issue(ImportRow row, ReasonCode code, String message) {
        return new ImportJob.RowIssue(row.rowNumber(), row.sellerId(), row.productId(), row.sizeId(),
                row.countryCode(), row.sourceVersion(), code.name(), message);
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
