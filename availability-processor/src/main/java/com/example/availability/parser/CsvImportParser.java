package com.example.availability.parser;

import com.example.availability.exception.InvalidInputException;
import com.example.availability.model.ImportRow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses an availability CSV upload.
 * <ul>
 *     <li>Request-level problems (empty body, missing/duplicated header columns, no data rows) throw
 *     {@link InvalidInputException} and the upload is rejected synchronously with 400.</li>
 *     <li>Row-level problems are attached to the row ({@link ImportRow#formatError()}) or found later
 *     by the shared validator, and reported in the job status.</li>
 * </ul>
 * Supports LF and CRLF line endings, a UTF-8 BOM, columns in any order, extra (ignored) columns and
 * RFC 4180 quoted fields on a single line. Values are trimmed.
 */
public final class CsvImportParser {

    public static final List<String> REQUIRED_COLUMNS = List.of(
            "sellerId", "productId", "sizeId", "countryCode", "availableQuantity", "sourceVersion");

    private CsvImportParser() {
    }

    public static List<ImportRow> parse(String content) {
        if (content == null || content.isBlank()) {
            throw new InvalidInputException(List.of("CSV body is empty"));
        }
        String text = content.charAt(0) == '﻿' ? content.substring(1) : content;
        List<String> lines = text.lines().toList();

        Map<String, Integer> columnIndex = readHeader(lines.get(0));
        int columnCount = columnIndex.size();

        List<ImportRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            int rowNumber = i + 1;
            List<String> fields;
            try {
                fields = splitLine(line);
            } catch (IllegalArgumentException e) {
                rows.add(ImportRow.malformed(rowNumber, e.getMessage()));
                continue;
            }
            if (fields.size() != columnCount) {
                rows.add(ImportRow.malformed(rowNumber,
                        "expected " + columnCount + " fields but found " + fields.size()));
                continue;
            }
            rows.add(new ImportRow(rowNumber,
                    fields.get(columnIndex.get("sellerId")),
                    fields.get(columnIndex.get("productId")),
                    fields.get(columnIndex.get("sizeId")),
                    fields.get(columnIndex.get("countryCode")),
                    fields.get(columnIndex.get("availableQuantity")),
                    fields.get(columnIndex.get("sourceVersion")),
                    null));
        }
        if (rows.isEmpty()) {
            throw new InvalidInputException(List.of("CSV contains no data rows"));
        }
        return rows;
    }

    private static Map<String, Integer> readHeader(String headerLine) {
        List<String> header;
        try {
            header = splitLine(headerLine);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException(List.of("invalid CSV header: " + e.getMessage()));
        }
        Map<String, Integer> index = new HashMap<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (int i = 0; i < header.size(); i++) {
            if (index.putIfAbsent(header.get(i), i) != null) {
                duplicates.add(header.get(i));
            }
        }
        List<String> errors = new ArrayList<>();
        if (!duplicates.isEmpty()) {
            errors.add("duplicated CSV column(s): " + String.join(", ", duplicates));
        }
        List<String> missing = REQUIRED_COLUMNS.stream().filter(c -> !index.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            errors.add("missing required CSV column(s): " + String.join(", ", missing)
                    + "; expected header: " + String.join(",", REQUIRED_COLUMNS));
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputException(errors);
        }
        return index;
    }

    /**
     * Splits one CSV line, honouring double-quoted fields and escaped quotes ({@code ""}).
     */
    static List<String> splitLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (inQuotes) {
            throw new IllegalArgumentException("unterminated quoted field");
        }
        fields.add(current.toString().trim());
        return fields;
    }
}
