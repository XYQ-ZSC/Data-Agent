package edu.zsc.ai.plugin.dm.value.template;

import edu.zsc.ai.plugin.value.DefaultValueProcessor;
import edu.zsc.ai.plugin.value.JdbcValueContext;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Processor for DM NUMBER/DECIMAL/NUMERIC types.
 * Converts BigDecimal to a plain (non-scientific) string so precision and scale
 * are preserved exactly.
 *
 * @author hhz
 */
public class DmDecimalProcessor extends DefaultValueProcessor {
    @Override
    public Object convertJdbcValueByType(JdbcValueContext context) throws SQLException {
        ResultSet resultSet = context.getResultSet();
        int columnIndex = context.getColumnIndex();
        java.math.BigDecimal decimal = resultSet.getBigDecimal(columnIndex);
        return decimal != null ? decimal.toPlainString() : null;
    }
}
