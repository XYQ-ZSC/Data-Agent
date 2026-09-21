package edu.zsc.ai.plugin.dm.value.template;

import edu.zsc.ai.plugin.value.DefaultValueProcessor;
import edu.zsc.ai.plugin.value.JdbcValueContext;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Processor for DM DATE/DATETIME types.
 *
 * <p>DM DATE is Oracle-compatible and always includes hour/minute/second, so it
 * is read as a {@link java.sql.Timestamp} and rendered as a LocalDateTime string.
 * If timestamp conversion fails for any driver-specific reason, falls back to the
 * raw string representation.
 *
 * @author hhz
 */
public class DmDateTimeProcessor extends DefaultValueProcessor {
    @Override
    public Object convertJdbcValueByType(JdbcValueContext context) throws SQLException {
        ResultSet resultSet = context.getResultSet();
        int columnIndex = context.getColumnIndex();

        try {
            java.sql.Timestamp timestamp = resultSet.getTimestamp(columnIndex);
            if (timestamp != null) {
                return timestamp.toLocalDateTime().toString();
            }
        } catch (SQLException e) {
            String stringValue = resultSet.getString(columnIndex);
            if (stringValue != null && !stringValue.isEmpty()) {
                return stringValue;
            }
        }
        return null;
    }
}
