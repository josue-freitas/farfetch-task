package com.example.availability.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockUpdateValidatorTest {

    @Test
    void acceptsAValidUpdate() {
        assertTrue(StockUpdateValidator.validate("seller-001", "style-1", "size-34", "PT", 0, 1L).isEmpty());
    }

    @Test
    void reportsIdentityAndRangeProblems() {
        List<String> errors = StockUpdateValidator.validate(null, "style-1", "size-34", "pt", -1, -5L);

        assertEquals(4, errors.size(), errors.toString());
        assertTrue(errors.contains("sellerId is required"));
        assertTrue(errors.contains("availableQuantity must be >= 0"));
        assertTrue(errors.contains("sourceVersion must be >= 0"));
    }

    @Test
    void acceptsAnyIsoCountry() {
        assertTrue(StockUpdateValidator.validate("s", "p", "z", "ES", 1, 1L).isEmpty());
        assertEquals(1, StockUpdateValidator.validate("s", "p", "z", "XX", 1, 1L).size());
    }
}
