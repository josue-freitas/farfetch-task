package com.example.availability.service;

import com.example.availability.dto.external.AvailabilityUpdateRequest;
import com.example.availability.dto.external.SizeAvailabilityUpdated;
import com.example.availability.exception.PermanentDependencyException;
import com.example.availability.exception.ProductContextNotFoundException;
import com.example.availability.exception.TransientDependencyException;
import com.example.availability.gateway.AvailabilityGateway;
import com.example.availability.gateway.EventPublisher;
import com.example.availability.model.ProcessingResult;
import com.example.availability.model.ProductContext;
import com.example.availability.model.ProductContextSummary;
import com.example.availability.model.ReasonCode;
import com.example.availability.model.StockKey;
import com.example.availability.model.StockState;
import com.example.availability.model.StockUpdate;
import com.example.availability.repository.StockStateRepository;
import com.example.availability.util.OutputEventIds;
import com.example.availability.util.RetryPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.Optional;

/**
 * The shared business flow used by BOTH inventory events and CSV rows:
 * <ol>
 *     <li>version check against the latest applied version of the same stock key;</li>
 *     <li>product context lookup and business checks (exists / active / enabled in country);</li>
 *     <li>availability write (with retries);</li>
 *     <li>record the applied version;</li>
 *     <li>publish the {@code SizeAvailabilityUpdated} event (with retries).</li>
 * </ol>
 * All steps for one stock key run under that key's lock, so a concurrent newer update (e.g. an
 * event arriving while a bulk job runs) can never be overwritten by an older one.
 */
@ApplicationScoped
public class AvailabilityProcessor {

    private static final Logger LOG = Logger.getLogger(AvailabilityProcessor.class);

    private final StockStateRepository stockStates;
    private final AvailabilityGateway availabilityGateway;
    private final EventPublisher eventPublisher;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    @Inject
    public AvailabilityProcessor(StockStateRepository stockStates, AvailabilityGateway availabilityGateway,
                                 EventPublisher eventPublisher, RetryPolicy retryPolicy, Clock clock) {
        this.stockStates = stockStates;
        this.availabilityGateway = availabilityGateway;
        this.eventPublisher = eventPublisher;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    public ProcessingResult process(StockUpdate update, ContextResolver contextResolver) {
        ProcessingResult result = stockStates.withKeyLock(update.key(),
                () -> processUnderLock(update, contextResolver));
        LOG.debugf("%s %s v%d -> %s %s", update.origin(), update.key(), update.sourceVersion(),
                result.outcome(), result.reason() == null ? "" : result.reason());
        return result;
    }

    private ProcessingResult processUnderLock(StockUpdate update, ContextResolver contextResolver) {
        StockKey key = update.key();

        // 1. Version rules: only compared within the same stock key.
        Optional<StockState> current = stockStates.find(key);
        if (current.isPresent()) {
            StockState state = current.get();
            if (update.sourceVersion() < state.appliedVersion()) {
                return ProcessingResult.skipped(ReasonCode.STALE_VERSION,
                        "version " + update.sourceVersion() + " is older than applied version " + state.appliedVersion());
            }
            if (update.sourceVersion() == state.appliedVersion()) {
                if (state.published()) {
                    return ProcessingResult.skipped(ReasonCode.DUPLICATE_VERSION,
                            "version " + update.sourceVersion() + " was already applied and published");
                }
                // Availability was written earlier but the event was never published: finish the job.
                return publish(key, state, true);
            }
        }

        // 2. Enrichment and business checks.
        ProductContext context;
        try {
            context = contextResolver.resolve(key.contextKey());
        } catch (ProductContextNotFoundException e) {
            return ProcessingResult.rejected(ReasonCode.PRODUCT_CONTEXT_NOT_FOUND, e.getMessage());
        } catch (PermanentDependencyException e) {
            return ProcessingResult.rejected(ReasonCode.PRODUCT_CONTEXT_UNAVAILABLE, e.getMessage());
        } catch (TransientDependencyException e) {
            return ProcessingResult.failed(ReasonCode.PRODUCT_CONTEXT_UNAVAILABLE, e.getMessage());
        }
        if (!context.isActive()) {
            return ProcessingResult.rejected(ReasonCode.PRODUCT_INACTIVE,
                    "product " + key.productId() + " has status " + context.productStatus());
        }
        if (!context.isEnabledInCountry()) {
            return ProcessingResult.rejected(ReasonCode.PRODUCT_NOT_ENABLED_IN_COUNTRY,
                    "product " + key.productId() + " is not enabled in " + key.countryCode());
        }
        ProductContextSummary summary = ProductContextSummary.from(context);

        // 3. Availability write.
        AvailabilityUpdateRequest request =
                new AvailabilityUpdateRequest(update.availableQuantity(), update.sourceVersion(), summary);
        try {
            retryPolicy.run(() -> availabilityGateway.update(key, request));
        } catch (TransientDependencyException e) {
            return ProcessingResult.failed(ReasonCode.AVAILABILITY_UPDATE_FAILED, e.getMessage());
        } catch (PermanentDependencyException e) {
            return ProcessingResult.rejected(ReasonCode.AVAILABILITY_UPDATE_REJECTED, e.getMessage());
        }

        // 4. The write succeeded: this version is now the latest applied one, even if publishing fails.
        StockState applied = new StockState(update.sourceVersion(), update.availableQuantity(), summary, false);
        stockStates.save(key, applied);

        // 5. Output event.
        return publish(key, applied, false);
    }

    private ProcessingResult publish(StockKey key, StockState state, boolean republish) {
        String eventId = OutputEventIds.forStockVersion(key, state.appliedVersion());
        SizeAvailabilityUpdated event = SizeAvailabilityUpdated.of(eventId, clock.instant(), key,
                state.availableQuantity(), state.appliedVersion(), state.productContext());
        try {
            retryPolicy.run(() -> eventPublisher.publish(event));
        } catch (TransientDependencyException | PermanentDependencyException e) {
            LOG.warnf("Availability %s v%d applied but event %s not published: %s",
                    key, state.appliedVersion(), eventId, e.getMessage());
            return ProcessingResult.failed(ReasonCode.EVENT_PUBLISH_FAILED,
                    "availability version " + state.appliedVersion() + " was applied but the output event "
                            + "could not be published; redeliver the same version to republish it: " + e.getMessage(),
                    eventId);
        }
        stockStates.save(key, state.markPublished());
        return republish ? ProcessingResult.republished(eventId) : ProcessingResult.applied(eventId);
    }
}
