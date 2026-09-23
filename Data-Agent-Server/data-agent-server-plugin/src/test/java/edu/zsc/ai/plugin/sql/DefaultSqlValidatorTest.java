package edu.zsc.ai.plugin.sql;

import edu.zsc.ai.plugin.model.sql.SqlValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultSqlValidatorTest {

    @Test
    void rejectsSelectWithoutProjection() {
        SqlValidationResult result = DefaultSqlValidator.INSTANCE.validate("SELECT FROM T");

        assertFalse(result.valid());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    void extractsSimpleSelectTableAndColumns() {
        SqlValidationResult result = DefaultSqlValidator.INSTANCE.validate("SELECT id, \"a;b\" FROM T");

        assertTrue(result.valid());
        assertEquals(List.of("T"), result.tables());
        assertEquals(List.of("id", "\"a;b\""), result.columns());
    }

    @Test
    void rejectsUnclosedQuotedIdentifier() {
        assertFalse(DefaultSqlValidator.INSTANCE.validate("SELECT \"broken FROM T").valid());
    }
}
