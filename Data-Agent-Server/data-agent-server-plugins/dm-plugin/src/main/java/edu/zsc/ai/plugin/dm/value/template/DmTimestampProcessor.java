package edu.zsc.ai.plugin.dm.value.template;

import edu.zsc.ai.plugin.value.DefaultValueProcessor;
import edu.zsc.ai.plugin.value.JdbcValueContext;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Processor for DM TIMESTAMP type.
 * Converts to LocalDateTime string representation (fractional seconds preserved
 * via LocalDateTime.toString()).
 *
 * @author hhz
 */
public class DmTimestampProcessor extends DefaultValueProcessor {
    @Override
    public Object convertJdbcValueByType(JdbcValueContext context) throws SQLException {
        ResultSet resultSet = context.getResultSet();
        int columnIndex = context.getColumnIndex();
        java.sql.Timestamp timestamp = resultSet.getTimestamp(columnIndex);
        return timestamp != null ? timestamp.toLocalDateTime().toString() : null;
    }
}
