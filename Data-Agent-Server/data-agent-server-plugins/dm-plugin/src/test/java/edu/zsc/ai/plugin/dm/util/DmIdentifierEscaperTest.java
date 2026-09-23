package edu.zsc.ai.plugin.dm.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DmIdentifierEscaperTest {

    private final DmIdentifierEscaper escaper = DmIdentifierEscaper.getInstance();

    @Test
    void singletonInstance() {
        assertSame(escaper, DmIdentifierEscaper.getInstance());
    }

    @Test
    void escapeIdentifierReturnsNullForNull() {
        assertNull(escaper.escapeIdentifier(null));
    }

    @Test
    void escapeIdentifierKeepsPlainIdentifier() {
        assertEquals("users", escaper.escapeIdentifier("users"));
    }

    @Test
    void escapeIdentifierDoublesEmbeddedDoubleQuotes() {
        assertEquals("we\"\"ird", escaper.escapeIdentifier("we\"ird"));
        assertEquals("\"\"", escaper.escapeIdentifier("\""));
    }

    @Test
    void quoteIdentifierPreservesExactCaseForSimpleIdentifiers() {
        assertEquals("\"users\"", escaper.quoteIdentifier("users"));
        assertEquals("\"SYSDBA\"", escaper.quoteIdentifier("SYSDBA"));
        assertEquals("\"_id1\"", escaper.quoteIdentifier("_id1"));
    }

    @Test
    void quoteIdentifierWrapsIdentifiersNeedingQuoting() {
        assertEquals("\"my table\"", escaper.quoteIdentifier("my table"));
        assertEquals("\"1abc\"", escaper.quoteIdentifier("1abc"));
        assertEquals("\"order-details\"", escaper.quoteIdentifier("order-details"));
    }

    @Test
    void quoteIdentifierEscapesEmbeddedQuotesBeforeWrapping() {
        assertEquals("\"we\"\"ird\"", escaper.quoteIdentifier("we\"ird"));
    }

    @Test
    void quoteIdentifierReturnsEmptyOrNullAsIs() {
        assertEquals("", escaper.quoteIdentifier(""));
        assertNull(escaper.quoteIdentifier(null));
    }

    @Test
    void escapeStringLiteralDoublesSingleQuotesOnly() {
        assertNull(escaper.escapeStringLiteral(null));
        assertEquals("plain", escaper.escapeStringLiteral("plain"));
        assertEquals("it''s", escaper.escapeStringLiteral("it's"));
        // backslash has no special meaning in DM string literals
        assertEquals("a\\b", escaper.escapeStringLiteral("a\\b"));
        assertEquals("a\\b''c", escaper.escapeStringLiteral("a\\b'c"));
    }
}
