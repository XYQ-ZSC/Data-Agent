package edu.zsc.ai.plugin.dm.executor;

import edu.zsc.ai.plugin.dm.value.DmValueProcessor;
import edu.zsc.ai.plugin.model.command.sql.AbstractSqlExecutor;
import edu.zsc.ai.plugin.value.JdbcValueContext;
import edu.zsc.ai.plugin.value.ValueProcessor;

import java.sql.SQLException;

/**
 * DM (DaMeng) specific SQL executor that handles DM data type conversions properly.
 * Uses {@link DmValueProcessor} for type-specific value extraction.
 *
 * <p>Handles special types like TIMESTAMP WITH TIME ZONE, BLOB/CLOB/BFILE, BIT,
 * NUMBER precision, and DM's empty-string-as-NULL semantics through the
 * factory-based value processor system.
 *
 * @author hhz
 */
public class DmSqlExecutor extends AbstractSqlExecutor {

    private static final ValueProcessor VALUE_PROCESSOR = DmValueProcessor.INSTANCE;

    @Override
    protected Object getJdbcValue(JdbcValueContext context) throws SQLException {
        return VALUE_PROCESSOR.getJdbcValue(context);
    }
}
