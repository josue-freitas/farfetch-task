package com.example.availability.mapper;

import com.example.availability.dto.request.InventoryEventMessage;
import com.example.availability.exception.InvalidInputException;
import com.example.availability.model.ImportRow;
import com.example.availability.model.StockKey;
import com.example.availability.model.StockUpdate;
import com.example.availability.validation.StockUpdateValidator;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Converts both inputs (inventory event, CSV row) into the single {@link StockUpdate} model used by
 * the shared processing flow. Every conversion validates first and reports ALL problems at once.
 */
public final class StockUpdateMapper {

    public static final String EVENT_TYPE = "SellerInventoryChanged";
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private StockUpdateMapper() {
    }

    /**
     * @throws InvalidInputException when the message does not satisfy the event contract
     */
    public static StockUpdate fromEvent(InventoryEventMessage message) {
        if (message == null) {
            throw new InvalidInputException(List.of("request body is required"));
        }
        List<String> errors = new ArrayList<>();
        if (message.eventId() == null || message.eventId().isBlank()) {
            errors.add("eventId is required");
        }
        if (!EVENT_TYPE.equals(message.eventType())) {
            errors.add("eventType must be " + EVENT_TYPE);
        }
        if (message.schemaVersion() == null || message.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            errors.add("schemaVersion must be " + SUPPORTED_SCHEMA_VERSION);
        }
        if (message.occurredAt() == null || message.occurredAt().isBlank()) {
            errors.add("occurredAt is required");
        } else {
            try {
                OffsetDateTime.parse(message.occurredAt());
            } catch (DateTimeParseException e) {
                errors.add("occurredAt must be an ISO-8601 timestamp with offset, e.g. 2026-10-20T08:00:00Z");
            }
        }
        errors.addAll(StockUpdateValidator.validate(message.sellerId(), message.productId(), message.sizeId(),
                message.countryCode(), message.availableQuantity(), message.sourceVersion()));
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        StockKey key = new StockKey(message.sellerId(), message.productId(), message.sizeId(), message.countryCode());
        return new StockUpdate(key, message.availableQuantity(), message.sourceVersion(), "event " + message.eventId());
    }

    /**
     * Converts a raw CSV row (text values).
     *
     * @throws InvalidInputException with every problem found in the row
     */
    public static StockUpdate fromRow(ImportRow row, String origin) {
        if (row.formatError() != null) {
            throw new InvalidInputException(List.of(row.formatError()));
        }
        List<String> errors = new ArrayList<>();
        Integer quantity = parseWholeNumber("availableQuantity", row.availableQuantity(), errors, Integer::valueOf);
        Long version = parseWholeNumber("sourceVersion", row.sourceVersion(), errors, Long::valueOf);
        if (errors.isEmpty()) {
            errors.addAll(StockUpdateValidator.validate(row.sellerId(), row.productId(), row.sizeId(),
                    row.countryCode(), quantity, version));
        } else {
            errors.addAll(0, StockUpdateValidator.validateIdentity(row.sellerId(), row.productId(), row.sizeId(),
                    row.countryCode()));
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        StockKey key = new StockKey(row.sellerId(), row.productId(), row.sizeId(), row.countryCode());
        return new StockUpdate(key, quantity, version, origin);
    }

    private static <T> T parseWholeNumber(String field, String raw, List<String> errors,
                                          Function<String, T> parser) {
        if (raw == null || raw.isBlank()) {
            errors.add(field + " is required");
            return null;
        }
        try {
            return parser.apply(raw.trim());
        } catch (NumberFormatException e) {
            errors.add(field + " must be a whole number, got '" + raw + "'");
            return null;
        }
    }
}
