package edu.zsc.ai.plugin.dm.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Direct tests for the package-private row write SQL templates.
 */
class DmRowWriteSqlTemplateTest {

    private final DmRowWriteSqlTemplate template = new DmRowWriteSqlTemplate();

    @Test
    void buildInsertRowSql() {
        assertEquals("INSERT INTO \"HR\".\"users\" (\"id\", \"name\") VALUES (?, ?)",
                template.buildInsertRowSql("\"HR\".\"users\"", List.of("\"id\"", "\"name\"")));
    }

    @Test
    void buildInsertRowSqlSingleColumn() {
        assertEquals("INSERT INTO t (\"id\") VALUES (?)",
                template.buildInsertRowSql("t", List.of("\"id\"")));
    }

    @Test
    void buildDeleteRowSql() {
        assertEquals("DELETE FROM \"HR\".\"users\" WHERE \"id\" = ?",
                template.buildDeleteRowSql("\"HR\".\"users\"", "\"id\" = ?"));
    }

    @Test
    void buildUpdateRowSql() {
        assertEquals("UPDATE \"HR\".\"users\" SET \"name\" = ? WHERE \"id\" = ?",
                template.buildUpdateRowSql("\"HR\".\"users\"", "\"name\" = ?", "\"id\" = ?"));
    }

    @Test
    void buildCountMatchingRowsSql() {
        assertEquals("SELECT COUNT(*) AS total FROM \"HR\".\"users\" WHERE \"name\" IS NULL",
                template.buildCountMatchingRowsSql("\"HR\".\"users\"", "\"name\" IS NULL"));
    }
}
