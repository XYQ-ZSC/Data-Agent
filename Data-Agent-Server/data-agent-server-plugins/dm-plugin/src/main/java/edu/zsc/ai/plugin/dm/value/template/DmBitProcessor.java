package edu.zsc.ai.plugin.dm.value.template;

import edu.zsc.ai.plugin.value.DefaultValueProcessor;
import edu.zsc.ai.plugin.value.JdbcValueContext;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Processor for DM BIT/BOOLEAN types.
 * DM BIT stores 0/1 and is commonly used as boolean.
 *
 * @author hhz
 */
public class DmBitProcessor extends DefaultValueProcessor {
    @Override
    public Object convertJdbcValueByType(JdbcValueContext context) throws SQLException {
        ResultSet resultSet = context.getResultSet();
        int columnIndex = context.getColumnIndex();
        boolean value = resultSet.getBoolean(columnIndex);
        return resultSet.wasNull() ? null : value;
    }
}
