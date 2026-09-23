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
 *   <li>Null and empty-string JDBC results are kept distinct, according to
 *       the actual server and driver behavior.</li>
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
        // Preserve the driver's result: DM compatibility modes can differ in
        // whether an empty string round-trips as empty or as SQL NULL.
        Object value = resultSet.getObject(columnIndex);
        if (Objects.isNull(value)) {
            return null;
        }

        // Do not silently turn an empty JDBC string into SQL NULL.
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
