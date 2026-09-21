package edu.zsc.ai.plugin.dm.value.template;

import edu.zsc.ai.plugin.value.DefaultValueProcessor;
import edu.zsc.ai.plugin.value.JdbcValueContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;

/**
 * Processor for DM TIMESTAMP WITH TIME ZONE / TIMESTAMP WITH LOCAL TIME ZONE.
 *
 * <p>Prefers the JDBC 4.2 {@code getObject(..., OffsetDateTime.class)} mapping
 * (rendered as an ISO offset date-time string). If the DM driver does not support
 * that mapping for its proprietary value class, falls back to the raw string
 * representation, which always carries the zone/offset information.
 *
 * @author hhz
 */
public class DmTimestampWithTimeZoneProcessor extends DefaultValueProcessor {
    @Override
    public Object convertJdbcValueByType(JdbcValueContext context) throws SQLException {
        ResultSet resultSet = context.getResultSet();
        int columnIndex = context.getColumnIndex();

        try {
            OffsetDateTime offsetDateTime = resultSet.getObject(columnIndex, OffsetDateTime.class);
            if (offsetDateTime != null) {
                return offsetDateTime.toString();
            }
            return resultSet.wasNull() ? null : resultSet.getString(columnIndex);
        } catch (Exception e) {
            // Driver does not support OffsetDateTime mapping for this type
            return resultSet.getString(columnIndex);
        }
    }
}
