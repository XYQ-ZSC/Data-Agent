package edu.zsc.ai.plugin.dm;

import edu.zsc.ai.plugin.base.AbstractDatabasePlugin;
import edu.zsc.ai.plugin.capability.ColumnManager;
import edu.zsc.ai.plugin.capability.CommandExecutor;
import edu.zsc.ai.plugin.capability.ConnectionManager;
import edu.zsc.ai.plugin.capability.FunctionManager;
import edu.zsc.ai.plugin.capability.IndexManager;
import edu.zsc.ai.plugin.capability.ProcedureManager;
import edu.zsc.ai.plugin.capability.SchemaManager;
import edu.zsc.ai.plugin.capability.SqlSplitter;
import edu.zsc.ai.plugin.capability.TableManager;
import edu.zsc.ai.plugin.capability.TriggerManager;
import edu.zsc.ai.plugin.capability.ViewManager;
import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.dm.executor.DmSqlExecutor;
import edu.zsc.ai.plugin.dm.manager.DmColumnManager;
import edu.zsc.ai.plugin.dm.manager.DmConnectionManager;
import edu.zsc.ai.plugin.dm.manager.DmFunctionManager;
import edu.zsc.ai.plugin.dm.manager.DmIndexManager;
import edu.zsc.ai.plugin.dm.manager.DmProcedureManager;
import edu.zsc.ai.plugin.dm.manager.DmTableManager;
import edu.zsc.ai.plugin.dm.manager.DmTriggerManager;
import edu.zsc.ai.plugin.dm.manager.DmViewManager;
import edu.zsc.ai.plugin.dm.support.DmMetadataSupport;
import edu.zsc.ai.plugin.dm.support.DmObjectQuerySupport;
import edu.zsc.ai.plugin.driver.MavenCoordinates;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandRequest;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.db.TableRowValue;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import edu.zsc.ai.plugin.model.metadata.FunctionMetadata;
import edu.zsc.ai.plugin.model.metadata.IndexMetadata;
import edu.zsc.ai.plugin.model.metadata.ProcedureMetadata;
import edu.zsc.ai.plugin.model.metadata.TriggerMetadata;
import edu.zsc.ai.plugin.sql.DefaultSqlSplitter;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for DM (达梦) database plugins.
 * Wires together all DM capability managers.
 */
public abstract class DefaultDmPlugin extends AbstractDatabasePlugin
        implements ConnectionManager, CommandExecutor<SqlCommandRequest, SqlCommandResult>,
        SchemaManager, TableManager, ViewManager, ColumnManager, IndexManager,
        FunctionManager, ProcedureManager, TriggerManager, SqlSplitter {

    private final ConnectionManager connectionManager = new DmConnectionManager(
            this::getDriverClassName,
            this::getJdbcUrlTemplate,
            this::getDefaultPort
    );
    private final DmSqlExecutor sqlExecutor = new DmSqlExecutor();
    private final DmMetadataSupport metadataSupport = new DmMetadataSupport();
    private final DmObjectQuerySupport objectQuerySupport = new DmObjectQuerySupport();
    private final TableManager tableManager = new DmTableManager(metadataSupport);
    private final ViewManager viewManager = new DmViewManager(metadataSupport);
    private final ColumnManager columnManager = new DmColumnManager(metadataSupport);
    private final IndexManager indexManager = new DmIndexManager(objectQuerySupport);
    private final FunctionManager functionManager = new DmFunctionManager(objectQuerySupport);
    private final ProcedureManager procedureManager = new DmProcedureManager(objectQuerySupport);
    private final TriggerManager triggerManager = new DmTriggerManager(objectQuerySupport);

    @Override
    public boolean supportDatabase() {
        return false;
    }

    @Override
    public boolean supportSchema() {
        return true;
    }

    protected abstract String getDriverClassName();

    protected String getJdbcUrlTemplate() {
        return "jdbc:dm://%s:%d";
    }

    protected int getDefaultPort() {
        return 5236;
    }

    @Override
    public MavenCoordinates getDriverMavenCoordinates(String driverVersion) {
        String version = (driverVersion != null && !driverVersion.isEmpty()) ? driverVersion : "8.1.3.140";
        return new MavenCoordinates(
                "com.dameng",
                "DmJdbcDriver18",
                version
        );
    }

    // ========== SchemaManager ==========

    /**
     * DM has no catalog concept; the catalog argument (a placeholder coming from the
     * explorer tree) is ignored. System schemas (SYS*) are sorted last.
     */
    @Override
    public List<String> getSchemas(Connection connection, String catalog) {
        try {
            List<String> list = new ArrayList<>();
            try (ResultSet rs = connection.getMetaData().getSchemas()) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_SCHEM");
                    if (name != null && !name.isBlank()) {
                        list.add(name);
                    }
                }
            }
            list.sort((a, b) -> {
                boolean sysA = a.toUpperCase().startsWith("SYS");
                boolean sysB = b.toUpperCase().startsWith("SYS");
                if (sysA != sysB) {
                    return sysA ? 1 : -1;
                }
                return a.compareToIgnoreCase(b);
            });
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list schemas: " + e.getMessage(), e);
        }
    }

    // ========== ConnectionManager ==========

    @Override
    public Connection connect(ConnectionConfig config) {
        return connectionManager.connect(config);
    }

    @Override
    public boolean testConnection(ConnectionConfig config) {
        return connectionManager.testConnection(config);
    }

    @Override
    public void closeConnection(Connection connection) {
        connectionManager.closeConnection(connection);
    }

    @Override
    public DatabaseMetaData getMetaData(Connection connection) {
        return connectionManager.getMetaData(connection);
    }

    @Override
    public String getDatabaseProductVersion(Connection connection) {
        return connectionManager.getDatabaseProductVersion(connection);
    }

    @Override
    public String getDbmsInfo(Connection connection) {
        return connectionManager.getDbmsInfo(connection);
    }

    @Override
    public String getDriverInfo(Connection connection) {
        return connectionManager.getDriverInfo(connection);
    }

    // ========== CommandExecutor ==========

    @Override
    public SqlCommandResult executeCommand(SqlCommandRequest command) {
        return sqlExecutor.executeCommand(command);
    }

    // ========== SqlSplitter ==========

    @Override
    public List<String> split(String sql) {
        return DefaultSqlSplitter.INSTANCE.split(sql);
    }

    // ========== TableManager ==========

    @Override
    public String getTableDdl(Connection connection, String catalog, String schema, String tableName) {
        return tableManager.getTableDdl(connection, catalog, schema, tableName);
    }

    @Override
    public void deleteTable(Connection connection, String catalog, String schema, String tableName) {
        tableManager.deleteTable(connection, catalog, schema, tableName);
    }

    @Override
    public SqlCommandResult getTableData(Connection connection, String catalog, String schema,
                                         String tableName, int offset, int pageSize) {
        return tableManager.getTableData(connection, catalog, schema, tableName, offset, pageSize);
    }

    @Override
    public long getTableDataCount(Connection connection, String catalog, String schema, String tableName) {
        return tableManager.getTableDataCount(connection, catalog, schema, tableName);
    }

    @Override
    public SqlCommandResult getTableData(Connection connection, String catalog, String schema, String tableName,
                                         int offset, int pageSize, String whereClause,
                                         String orderByColumn, String orderByDirection) {
        return tableManager.getTableData(connection, catalog, schema, tableName, offset, pageSize,
                whereClause, orderByColumn, orderByDirection);
    }

    @Override
    public long getTableDataCount(Connection connection, String catalog, String schema,
                                  String tableName, String whereClause) {
        return tableManager.getTableDataCount(connection, catalog, schema, tableName, whereClause);
    }

    @Override
    public SqlCommandResult insertRow(Connection connection, String catalog, String schema, String tableName,
                                      List<TableRowValue> values) {
        return tableManager.insertRow(connection, catalog, schema, tableName, values);
    }

    @Override
    public SqlCommandResult deleteRow(Connection connection, String catalog, String schema, String tableName,
                                      List<TableRowValue> matchValues, boolean force) {
        return tableManager.deleteRow(connection, catalog, schema, tableName, matchValues, force);
    }

    @Override
    public SqlCommandResult updateRow(Connection connection, String catalog, String schema, String tableName,
                                      List<TableRowValue> setValues, List<TableRowValue> matchValues,
                                      boolean force) {
        return tableManager.updateRow(connection, catalog, schema, tableName, setValues, matchValues, force);
    }

    // ========== ViewManager ==========

    @Override
    public String getViewDdl(Connection connection, String catalog, String schema, String viewName) {
        return viewManager.getViewDdl(connection, catalog, schema, viewName);
    }

    @Override
    public void deleteView(Connection connection, String catalog, String schema, String viewName) {
        viewManager.deleteView(connection, catalog, schema, viewName);
    }

    @Override
    public SqlCommandResult getViewData(Connection connection, String catalog, String schema,
                                        String viewName, int offset, int pageSize) {
        return viewManager.getViewData(connection, catalog, schema, viewName, offset, pageSize);
    }

    @Override
    public long getViewDataCount(Connection connection, String catalog, String schema, String viewName) {
        return viewManager.getViewDataCount(connection, catalog, schema, viewName);
    }

    @Override
    public SqlCommandResult getViewData(Connection connection, String catalog, String schema, String viewName,
                                        int offset, int pageSize, String whereClause,
                                        String orderByColumn, String orderByDirection) {
        return viewManager.getViewData(connection, catalog, schema, viewName, offset, pageSize,
                whereClause, orderByColumn, orderByDirection);
    }

    @Override
    public long getViewDataCount(Connection connection, String catalog, String schema,
                                 String viewName, String whereClause) {
        return viewManager.getViewDataCount(connection, catalog, schema, viewName, whereClause);
    }

    // ========== ColumnManager ==========

    @Override
    public List<ColumnMetadata> getColumns(Connection connection, String catalog, String schema, String tableOrViewName) {
        return columnManager.getColumns(connection, catalog, schema, tableOrViewName);
    }

    // ========== IndexManager ==========

    @Override
    public List<IndexMetadata> getIndexes(Connection connection, String catalog, String schema, String tableName) {
        return indexManager.getIndexes(connection, catalog, schema, tableName);
    }

    // ========== FunctionManager ==========

    @Override
    public List<FunctionMetadata> getFunctions(Connection connection, String catalog, String schema) {
        return functionManager.getFunctions(connection, catalog, schema);
    }

    @Override
    public List<FunctionMetadata> searchFunctions(Connection connection, String catalog, String schema, String functionNamePattern) {
        return functionManager.searchFunctions(connection, catalog, schema, functionNamePattern);
    }

    @Override
    public long countFunctions(Connection connection, String catalog, String schema, String functionNamePattern) {
        return functionManager.countFunctions(connection, catalog, schema, functionNamePattern);
    }

    @Override
    public String getFunctionDdl(Connection connection, String catalog, String schema, String functionName) {
        return functionManager.getFunctionDdl(connection, catalog, schema, functionName);
    }

    @Override
    public void deleteFunction(Connection connection, String catalog, String schema, String functionName) {
        functionManager.deleteFunction(connection, catalog, schema, functionName);
    }

    // ========== ProcedureManager ==========

    @Override
    public List<ProcedureMetadata> getProcedures(Connection connection, String catalog, String schema) {
        return procedureManager.getProcedures(connection, catalog, schema);
    }

    @Override
    public List<ProcedureMetadata> searchProcedures(Connection connection, String catalog, String schema,
                                                    String procedureNamePattern) {
        return procedureManager.searchProcedures(connection, catalog, schema, procedureNamePattern);
    }

    @Override
    public long countProcedures(Connection connection, String catalog, String schema, String procedureNamePattern) {
        return procedureManager.countProcedures(connection, catalog, schema, procedureNamePattern);
    }

    @Override
    public String getProcedureDdl(Connection connection, String catalog, String schema, String procedureName) {
        return procedureManager.getProcedureDdl(connection, catalog, schema, procedureName);
    }

    @Override
    public void deleteProcedure(Connection connection, String catalog, String schema, String procedureName) {
        procedureManager.deleteProcedure(connection, catalog, schema, procedureName);
    }

    // ========== TriggerManager ==========

    @Override
    public List<TriggerMetadata> getTriggers(Connection connection, String catalog, String schema, String tableName) {
        return triggerManager.getTriggers(connection, catalog, schema, tableName);
    }

    @Override
    public String getTriggerDdl(Connection connection, String catalog, String schema, String triggerName) {
        return triggerManager.getTriggerDdl(connection, catalog, schema, triggerName);
    }

    @Override
    public void deleteTrigger(Connection connection, String catalog, String schema, String triggerName) {
        triggerManager.deleteTrigger(connection, catalog, schema, triggerName);
    }
}
