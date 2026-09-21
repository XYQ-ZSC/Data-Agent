package edu.zsc.ai.plugin.dm.manager;

import java.sql.Connection;
import java.util.Objects;

import edu.zsc.ai.plugin.capability.ViewManager;
import edu.zsc.ai.plugin.constant.DatabaseObjectTypeEnum;
import edu.zsc.ai.plugin.dm.constant.DmSqlTemplate;
import edu.zsc.ai.plugin.dm.support.DmMetadataSupport;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;

/**
 * DM view manager. View listing/counting reuse the SPI default
 * {@code DatabaseMetaData}-based implementations; DDL, DROP and paginated
 * data access are implemented with DM-specific SQL.
 */
public final class DmViewManager implements ViewManager {

    private final DmMetadataSupport support;

    public DmViewManager(DmMetadataSupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    public String getViewDdl(Connection connection, String catalog, String schema, String viewName) {
        return support.getObjectDdl(
                connection,
                schema,
                viewName,
                DmSqlTemplate.SQL_GET_VIEW_DDL,
                DatabaseObjectTypeEnum.VIEW.getValue()
        );
    }

    @Override
    public void deleteView(Connection connection, String catalog, String schema, String viewName) {
        support.dropObject(
                connection,
                schema,
                viewName,
                DmSqlTemplate.SQL_DROP_VIEW,
                DatabaseObjectTypeEnum.VIEW
        );
    }

    @Override
    public SqlCommandResult getViewData(Connection connection, String catalog, String schema,
                                        String viewName, int offset, int pageSize) {
        return support.getTableLikeData(connection, schema, viewName, offset, pageSize);
    }

    @Override
    public long getViewDataCount(Connection connection, String catalog, String schema, String viewName) {
        return support.getTableLikeDataCount(connection, schema, viewName);
    }

    @Override
    public SqlCommandResult getViewData(Connection connection, String catalog, String schema, String viewName,
                                        int offset, int pageSize, String whereClause,
                                        String orderByColumn, String orderByDirection) {
        return support.getTableLikeData(
                connection,
                schema,
                viewName,
                offset,
                pageSize,
                whereClause,
                orderByColumn,
                orderByDirection
        );
    }

    @Override
    public long getViewDataCount(Connection connection, String catalog, String schema,
                                 String viewName, String whereClause) {
        return support.getTableLikeDataCount(connection, schema, viewName, whereClause);
    }
}
