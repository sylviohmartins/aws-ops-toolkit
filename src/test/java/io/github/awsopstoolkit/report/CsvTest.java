package io.github.awsopstoolkit.report;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CsvTest {
    @Test
    void quotesEmbeddedDelimitersQuotesAndNewlines() {
        assertEquals("\"a,\"\"b\"\"\n\"", Csv.cell("a,\"b\"\n"));
        assertEquals("\"\"", Csv.cell(null));
    }

    @Test
    void prefixesFormulaAndControlCharacterCells() {
        assertEquals("\"'=1+1\"", Csv.cell("=1+1"));
        assertEquals("\"'  @SUM(1)\"", Csv.cell("  @SUM(1)"));
        assertEquals("\"'\tvalue\"", Csv.cell("\tvalue"));
    }
}
