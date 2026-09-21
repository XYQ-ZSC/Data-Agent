package edu.zsc.ai.plugin.dm.value;

import edu.zsc.ai.plugin.dm.value.template.DmValueProcessorFactory;
import edu.zsc.ai.plugin.value.DefaultValueProcessor;
import edu.zsc.ai.plugin.value.JdbcValueContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * DM (DaMeng) specific value processor that handles DM data type conversions.
 *
 * <p>DM-specific semantics handled here:
 * <ul>
 *   <li>Empty string is stored/returned as SQL NULL (Oracle-compatible), so a
 *       {@code null} value from the driver is returned directly without the
 *       MySQL-style "0000-00-00" string fallback (DM rejects invalid dates at
 *       write time, that case cannot occur).</li>
 *   <li>BLOB/CLOB/BFILE streaming extraction, TIMESTAMP WITH TIME ZONE, BIT and
 *       NUMBER precision are delegated to type-specific processors via
 *       {@link DmValueProcessorFactory}.</li>
 * </ul>
 *
 * @author hhz
 */
public class DmValueProcessor extends DefaultValueProcessor {

    public static final DmValueProcessor INSTANCE = new DmValueProcessor();

    private DmValueProcessor() {
    }

    private static final Logger log = LoggerFactory.getLogger(DmValueProcessor.class);

    @Override
    public Object getJdbcValue(JdbcValueContext context) throws SQLException {
        ResultSet resultSet = context.getResultSet();
        int columnIndex = context.getColumnIndex();

        // First check if value is null.
        // DM treats empty string as NULL (Oracle-compatible semantics), so a null
        // from the driver already covers the empty-string case. Unlike MySQL there
        // are no invalid-date literals ("0000-00-00") to recover, return null as-is.
        Object value = resultSet.getObject(columnIndex);
        if (Objects.isNull(value)) {
            return null;
        }

        // Defensive: an actual empty string should not survive a round-trip in DM,
        // but keep it if the driver ever hands one back.
        if (value instanceof String emptyStr && emptyStr.isEmpty()) {
            return emptyStr;
        }

        // Delegate to type-specific processor via factory
        return convertJdbcValueByType(context);
    }

    @Override
    public Object convertJdbcValueByType(JdbcValueContext context) throws SQLException {
        try {
            // Try to get type-specific processor from factory
            DefaultValueProcessor typeProcessor = DmValueProcessorFactory.getValueProcessor(context.getColumnTypeName());
            if (Objects.nonNull(typeProcessor)) {
                return typeProcessor.convertJdbcValueByType(context);
            }
        } catch (Exception e) {
            log.warn("Error using type-specific processor for type: {}", context.getColumnTypeName(), e);
        }

        // Fallback to default conversion
        return super.convertJdbcValueByType(context);
    }
}
