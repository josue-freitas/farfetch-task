package com.example.availability.parser;

import com.example.availability.exception.InvalidInputException;
import com.example.availability.model.ImportRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvImportParserTest {

    private static final String HEADER = "sellerId,productId,sizeId,countryCode,availableQuantity,sourceVersion";

    @Test
    void parsesRowsWithFileLineNumbersCrlfAndBom() {
        String csv = "﻿" + HEADER + "\r\n"
                + "seller-001,style-1,size-34,PT,0,1\r\n"
                + "\r\n"
                + "seller-002,style-1,size-36,PT,1,1\r\n";

        List<ImportRow> rows = CsvImportParser.parse(csv);

        assertEquals(2, rows.size());
        assertEquals(2, rows.get(0).rowNumber());
        assertEquals("seller-001", rows.get(0).sellerId());
        assertEquals("1", rows.get(0).sourceVersion());
        assertEquals(4, rows.get(1).rowNumber());
        assertNull(rows.get(1).formatError());
    }

    @Test
    void acceptsColumnsInAnyOrderIgnoresExtraColumnsAndHandlesQuotes() {
        String csv = "sourceVersion,availableQuantity,note,countryCode,sizeId,productId,sellerId\n"
                + "7,3,\"hello, world\",PT,size-38,style-123,\"boutique-42\"\n";

        ImportRow row = CsvImportParser.parse(csv).get(0);

        assertEquals("boutique-42", row.sellerId());
        assertEquals("style-123", row.productId());
        assertEquals("3", row.availableQuantity());
        assertEquals("7", row.sourceVersion());
    }

    @Test
    void rejectsMissingHeaderColumnsSynchronously() {
        InvalidInputException e = assertThrows(InvalidInputException.class,
                () -> CsvImportParser.parse("sellerId,productId,sizeId\nseller-001,style-1,size-34\n"));

        assertTrue(e.errors().get(0).startsWith(
                "missing required CSV column(s): countryCode, availableQuantity, sourceVersion"), e.errors().toString());
    }

    @Test
    void rejectsDuplicatedHeaderColumns() {
        assertThrows(InvalidInputException.class,
                () -> CsvImportParser.parse(HEADER + ",sellerId\ns,p,z,PT,1,1,s\n"));
    }

    @Test
    void rejectsEmptyBodyAndHeaderWithoutRows() {
        assertThrows(InvalidInputException.class, () -> CsvImportParser.parse(""));
        assertThrows(InvalidInputException.class, () -> CsvImportParser.parse(HEADER + "\n"));
    }

    @Test
    void keepsStructurallyBrokenRowsAsRowLevelErrors() {
        String csv = HEADER + "\n"
                + "seller-001,style-1,size-34,PT,0\n"
                + "seller-001,\"style-1,size-34,PT,0,1\n"
                + "seller-002,style-1,size-36,PT,1,1\n";

        List<ImportRow> rows = CsvImportParser.parse(csv);

        assertEquals(3, rows.size());
        assertEquals("expected 6 fields but found 5", rows.get(0).formatError());
        assertEquals("unterminated quoted field", rows.get(1).formatError());
        assertNull(rows.get(2).formatError());
    }
}
