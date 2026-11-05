package com.example.availability.model;

/**
 * One raw CSV data row. Values are kept as text; they are validated row by row during processing
 * so that one bad row does not reject the whole upload.
 *
 * @param rowNumber   1-based line number in the uploaded file (the header is line 1)
 * @param formatError set when the line itself could not be split into the expected columns
 */
public record ImportRow(
        int rowNumber,
        String sellerId,
        String productId,
        String sizeId,
        String countryCode,
        String availableQuantity,
        String sourceVersion,
        String formatError) {

    public static ImportRow malformed(int rowNumber, String formatError) {
        return new ImportRow(rowNumber, null, null, null, null, null, null, formatError);
    }
}
