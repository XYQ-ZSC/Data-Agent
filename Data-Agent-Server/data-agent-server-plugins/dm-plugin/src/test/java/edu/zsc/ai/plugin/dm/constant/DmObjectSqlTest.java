package edu.zsc.ai.plugin.dm.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DmObjectSqlTest {

    @Test
    void objectTypeConstants() {
        assertEquals("FUNCTION", DmObjectSql.OBJECT_TYPE_FUNCTION);
        assertEquals("PROCEDURE", DmObjectSql.OBJECT_TYPE_PROCEDURE);
        assertEquals("TRIGGER", DmObjectSql.OBJECT_TYPE_TRIGGER);
        assertEquals("INDEX", DmObjectSql.OBJECT_TYPE_INDEX);
    }

    @Test
    void routineListQueryTargetsAllObjects() {
        assertTrue(DmObjectSql.SQL_LIST_ROUTINES.contains("FROM ALL_OBJECTS"));
        assertTrue(DmObjectSql.SQL_LIST_ROUTINES.contains("UPPER(OWNER) = UPPER(?)"));
        assertTrue(DmObjectSql.SQL_LIST_ROUTINES.contains("OBJECT_TYPE = ?"));
        assertTrue(DmObjectSql.SQL_COUNT_ROUTINES.startsWith("SELECT COUNT(*) AS TOTAL FROM ALL_OBJECTS"));
        assertEquals(" AND UPPER(OBJECT_NAME) LIKE UPPER(?)", DmObjectSql.SQL_ROUTINES_NAME_CLAUSE);
        assertEquals(" ORDER BY OBJECT_NAME", DmObjectSql.SQL_ROUTINES_ORDER_BY);
    }

    @Test
    void argumentsQueryExcludesReturnValuePosition() {
        assertTrue(DmObjectSql.SQL_FETCH_ARGUMENTS.contains("FROM ALL_ARGUMENTS"));
        assertTrue(DmObjectSql.SQL_FETCH_ARGUMENTS.contains("POSITION > 0"));
        assertTrue(DmObjectSql.SQL_FETCH_ARGUMENTS.contains("OBJECT_NAME IN (%s)"));
    }

    @Test
    void triggerQueriesTargetAllTriggers() {
        assertTrue(DmObjectSql.SQL_LIST_TRIGGERS.contains("FROM ALL_TRIGGERS"));
        assertTrue(DmObjectSql.SQL_LIST_TRIGGERS.contains("TRIGGERING_EVENT"));
        assertEquals(" AND UPPER(TABLE_NAME) = UPPER(?)", DmObjectSql.SQL_TRIGGER_TABLE_CLAUSE);
    }

    @Test
    void indexQueriesDetectPrimaryKeyBacking() {
        assertTrue(DmObjectSql.SQL_LIST_INDEXES.contains("FROM ALL_INDEXES i"));
        assertTrue(DmObjectSql.SQL_LIST_INDEXES.contains("ALL_CONSTRAINTS"));
        assertTrue(DmObjectSql.SQL_LIST_INDEXES.contains("IS_PRIMARY"));
        assertTrue(DmObjectSql.SQL_LIST_INDEX_COLUMNS.contains("FROM ALL_IND_COLUMNS"));
    }

    @Test
    void objectDdlUsesDbmsMetadataWithEscapedLiterals() {
        assertEquals("SELECT DBMS_METADATA.GET_DDL('FUNCTION', 'F1', 'HR') AS DDL FROM DUAL",
                String.format(DmObjectSql.SQL_GET_OBJECT_DDL, "FUNCTION", "F1", "HR"));
    }

    @Test
    void dropStatementsFormatQuotedIdentifiers() {
        assertEquals("DROP FUNCTION \"HR\".\"f1\"",
                String.format(DmObjectSql.SQL_DROP_FUNCTION, "\"HR\".\"f1\""));
        assertEquals("DROP PROCEDURE \"HR\".\"p1\"",
                String.format(DmObjectSql.SQL_DROP_PROCEDURE, "\"HR\".\"p1\""));
        assertEquals("DROP TRIGGER \"HR\".\"t1\"",
                String.format(DmObjectSql.SQL_DROP_TRIGGER, "\"HR\".\"t1\""));
    }

    @Test
    void currentSchemaFallbackQuery() {
        assertEquals("SELECT USER FROM DUAL", DmObjectSql.SQL_CURRENT_SCHEMA);
    }
}
