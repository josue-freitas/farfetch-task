package com.example.availability.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validation rules shared by inventory events and CSV rows, so both input paths accept exactly the
 * same stock updates. Methods return the list of problems (empty when valid).
 */
public final class StockUpdateValidator {

    private static final Set<String> ISO_COUNTRY_CODES = Set.of(Locale.getISOCountries());

    private StockUpdateValidator() {
    }

    public static List<String> validate(String sellerId, String productId, String sizeId, String countryCode,
                                        Integer availableQuantity, Long sourceVersion) {
        List<String> errors = validateIdentity(sellerId, productId, sizeId, countryCode);
        if (availableQuantity == null) {
            errors.add("availableQuantity is required");
        } else if (availableQuantity < 0) {
            errors.add("availableQuantity must be >= 0");
        }
        if (sourceVersion == null) {
            errors.add("sourceVersion is required");
        } else if (sourceVersion < 0) {
            errors.add("sourceVersion must be >= 0");
        }
        return errors;
    }

    /**
     * Validates the stock key fields only.
     */
    public static List<String> validateIdentity(String sellerId, String productId, String sizeId, String countryCode) {
        List<String> errors = new ArrayList<>();
        requireText(errors, "sellerId", sellerId);
        requireText(errors, "productId", productId);
        requireText(errors, "sizeId", sizeId);
        if (isBlank(countryCode)) {
            errors.add("countryCode is required");
        } else if (!ISO_COUNTRY_CODES.contains(countryCode)) {
            errors.add("countryCode must be an upper-case ISO 3166-1 alpha-2 code, got '" + countryCode + "'");
        }
        return errors;
    }

    private static void requireText(List<String> errors, String field, String value) {
        if (isBlank(value)) {
            errors.add(field + " is required");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
