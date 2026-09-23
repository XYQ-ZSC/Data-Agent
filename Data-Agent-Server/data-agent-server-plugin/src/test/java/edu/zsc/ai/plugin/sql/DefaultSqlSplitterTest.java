package edu.zsc.ai.plugin.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefaultSqlSplitterTest {

    @Test
    void keepsSemicolonInsideQuotedIdentifier() {
        assertEquals(List.of("SELECT \"a;b\" FROM T"),
                DefaultSqlSplitter.INSTANCE.split("SELECT \"a;b\" FROM T;"));
    }

    @Test
    void keepsAnonymousBlockTogether() {
        assertEquals(List.of("BEGIN NULL; NULL; END;"),
                DefaultSqlSplitter.INSTANCE.split("BEGIN NULL; NULL; END;"));
    }
}
