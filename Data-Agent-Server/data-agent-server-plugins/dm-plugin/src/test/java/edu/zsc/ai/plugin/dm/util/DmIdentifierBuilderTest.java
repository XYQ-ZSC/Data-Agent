package edu.zsc.ai.plugin.dm.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DmIdentifierBuilderTest {

    @Test
    void buildFullIdentifierWithSchema() {
        assertEquals("\"SYSDBA\".\"users\"", DmIdentifierBuilder.buildFullIdentifier("SYSDBA", "users"));
    }

    @Test
    void buildFullIdentifierQuotesPartsThatNeedIt() {
        assertEquals("\"my schema\".\"my table\"",
                DmIdentifierBuilder.buildFullIdentifier("my schema", "my table"));
    }

    @Test
    void buildFullIdentifierEscapesEmbeddedQuotes() {
        assertEquals("\"sch\"\"em\".\"t\"", DmIdentifierBuilder.buildFullIdentifier("sch\"em", "t"));
    }

    @Test
    void buildFullIdentifierWithoutSchema() {
        assertEquals("\"users\"", DmIdentifierBuilder.buildFullIdentifier(null, "users"));
        assertEquals("\"users\"", DmIdentifierBuilder.buildFullIdentifier("", "users"));
        assertEquals("\"users\"", DmIdentifierBuilder.buildFullIdentifier("   ", "users"));
    }

    @Test
    void buildFullIdentifierRejectsBlankObjectName() {
        assertThrows(IllegalArgumentException.class,
                () -> DmIdentifierBuilder.buildFullIdentifier("SYSDBA", null));
        assertThrows(IllegalArgumentException.class,
                () -> DmIdentifierBuilder.buildFullIdentifier("SYSDBA", "  "));
    }
}
