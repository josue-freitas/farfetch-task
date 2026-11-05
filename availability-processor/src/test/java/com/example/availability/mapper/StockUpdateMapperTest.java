package com.example.availability.mapper;

import com.example.availability.dto.request.InventoryEventMessage;
import com.example.availability.exception.InvalidInputException;
import com.example.availability.model.ImportRow;
import com.example.availability.model.StockKey;
import com.example.availability.model.StockUpdate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockUpdateMapperTest {

    static InventoryEventMessage sampleEvent() {
        return new InventoryEventMessage("msg-0001", "SellerInventoryChanged", 1, "2026-10-20T08:00:00Z",
                "boutique-42", "style-123", "size-38", "PT", 3, 7L);
    }

    @Test
    void convertsAValidEvent() {
        StockUpdate update = StockUpdateMapper.fromEvent(sampleEvent());

        assertEquals(new StockKey("boutique-42", "style-123", "size-38", "PT"), update.key());
        assertEquals(3, update.availableQuantity());
        assertEquals(7L, update.sourceVersion());
    }

    @Test
    void reportsEveryEventContractViolationAtOnce() {
        InventoryEventMessage invalid = new InventoryEventMessage("", "SomethingElse", 2, "yesterday",
                "boutique-42", " ", "size-38", "Portugal", -1, null);

        InvalidInputException e = assertThrows(InvalidInputException.class,
                () -> StockUpdateMapper.fromEvent(invalid));

        assertEquals(8, e.errors().size(), e.errors().toString());
        assertTrue(e.errors().contains("eventType must be SellerInventoryChanged"));
        assertTrue(e.errors().contains("productId is required"));
        assertTrue(e.errors().contains("sourceVersion is required"));
    }

    @Test
    void rejectsMissingEventBody() {
        assertThrows(InvalidInputException.class, () -> StockUpdateMapper.fromEvent(null));
    }

    @Test
    void convertsAValidRow() {
        StockUpdate update = StockUpdateMapper.fromRow(row("0", "1"), "row 2");

        assertEquals(new StockKey("seller-001", "style-1", "size-34", "PT"), update.key());
        assertEquals(0, update.availableQuantity());
        assertEquals(1L, update.sourceVersion());
    }

    @Test
    void reportsNonNumericRowValuesOnce() {
        InvalidInputException e = assertThrows(InvalidInputException.class,
                () -> StockUpdateMapper.fromRow(row("abc", ""), "row 2"));

        assertEquals(List.of(
                "availableQuantity must be a whole number, got 'abc'",
                "sourceVersion is required"), e.errors());
    }

    @Test
    void reportsStructurallyBrokenRows() {
        InvalidInputException e = assertThrows(InvalidInputException.class,
                () -> StockUpdateMapper.fromRow(ImportRow.malformed(3, "expected 6 fields but found 5"), "row 3"));

        assertEquals(List.of("expected 6 fields but found 5"), e.errors());
    }

    private static ImportRow row(String quantity, String version) {
        return new ImportRow(2, "seller-001", "style-1", "size-34", "PT", quantity, version, null);
    }
}
