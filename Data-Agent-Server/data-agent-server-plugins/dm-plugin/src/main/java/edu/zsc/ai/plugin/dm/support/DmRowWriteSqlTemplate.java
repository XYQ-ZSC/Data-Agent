package edu.zsc.ai.plugin.dm.support;

import java.util.Collections;
import java.util.List;

/**
 * Row-level write SQL templates for DM (DaMeng).
 *
 * <p>Templates are kept locally instead of a shared constants class so this
 * package stays self-contained; they are standard SQL that DM accepts as-is.
 *
 * @author hhz
 */
final class DmRowWriteSqlTemplate {

    private static final String SQL_INSERT_TABLE_ROW =
            "INSERT INTO %s (%s) VALUES (%s)";

    private static final String SQL_DELETE_TABLE_ROW =
            "DELETE FROM %s WHERE %s";

    private static final String SQL_UPDATE_TABLE_ROW =
            "UPDATE %s SET %s WHERE %s";

    private static final String SQL_COUNT_MATCHING_TABLE_ROWS =
            "SELECT COUNT(*) AS total FROM %s WHERE %s";

    String buildInsertRowSql(String fullTableName, List<String> quotedColumns) {
        String placeholders = String.join(", ", Collections.nCopies(quotedColumns.size(), "?"));
        return String.format(
                SQL_INSERT_TABLE_ROW,
                fullTableName,
                String.join(", ", quotedColumns),
                placeholders
        );
    }

    String buildDeleteRowSql(String fullTableName, String whereSql) {
        return String.format(SQL_DELETE_TABLE_ROW, fullTableName, whereSql);
    }

    String buildUpdateRowSql(String fullTableName, String setClauseSql, String whereSql) {
        return String.format(SQL_UPDATE_TABLE_ROW, fullTableName, setClauseSql, whereSql);
    }

    String buildCountMatchingRowsSql(String fullTableName, String whereSql) {
        return String.format(SQL_COUNT_MATCHING_TABLE_ROWS, fullTableName, whereSql);
    }
}
