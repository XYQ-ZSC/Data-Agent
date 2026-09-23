package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.common.converter.db.SqlExecutionConverter;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.AgentExecuteSqlRequest;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.service.db.ConnectionAccessService;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.SqlExecutionService;
import edu.zsc.ai.plugin.capability.CommandExecutor;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandRequest;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqlExecutionServiceImpl implements SqlExecutionService {

    private final ConnectionService connectionService;
    private final ConnectionAccessService connectionAccessService;

    @Override
    public ExecuteSqlResponse executeSql(AgentExecuteSqlRequest request) {
        connectionAccessService.assertWorkbenchApiAllowed();
        DbContext db = DbContext.from(request);
        String sql = request.getSql();

        if (isTransactionControl(sql)) {
            return failedResponse(sql, db,
                    "Explicit transaction control is not supported; submit the statements as one batch");
        }

        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        CommandExecutor<SqlCommandRequest, SqlCommandResult> executor = DefaultPluginManager.getInstance()
                .getSqlCommandExecutorByPluginId(active.pluginId());
        SqlCommandResult result;
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            SqlCommandRequest pluginRequest = new SqlCommandRequest();
            pluginRequest.setConnection(borrowed.connection());
            pluginRequest.setOriginalSql(sql);
            pluginRequest.setExecuteSql(sql);
            pluginRequest.setDatabase(db.catalog());
            pluginRequest.setSchema(db.schema());
            pluginRequest.setNeedTransaction(true);
            result = executor.executeCommand(pluginRequest);
        }

        ExecuteSqlResponse response = SqlExecutionConverter.toResponse(result);
        if (response != null) {
            response.setDatabaseName(db.catalog());
            response.setSchemaName(db.schema());
        }
        return response;
    }

    @Override
    public List<ExecuteSqlResponse> executeBatchSql(DbContext db, List<String> sqls) {
        if (sqls.stream().anyMatch(this::isTransactionControl)) {
            return sqls.stream()
                    .map(sql -> failedResponse(sql, db,
                            "Explicit transaction control is not supported; the batch is already transactional"))
                    .toList();
        }

        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        CommandExecutor<SqlCommandRequest, SqlCommandResult> executor = DefaultPluginManager.getInstance()
                .getSqlCommandExecutorByPluginId(active.pluginId());

        List<ExecuteSqlResponse> responses = new ArrayList<>(sqls.size());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            Connection connection = borrowed.connection();
            boolean originalAutoCommit = getAutoCommit(connection);
            try {
                connection.setAutoCommit(false);
                for (int i = 0; i < sqls.size(); i++) {
                    String sql = sqls.get(i);
                    SqlCommandRequest pluginRequest = new SqlCommandRequest();
                    pluginRequest.setConnection(connection);
                    pluginRequest.setOriginalSql(sql);
                    pluginRequest.setExecuteSql(sql);
                    pluginRequest.setDatabase(db.catalog());
                    pluginRequest.setSchema(db.schema());
                    pluginRequest.setNeedTransaction(false);

                    SqlCommandResult result = executor.executeCommand(pluginRequest);
                    ExecuteSqlResponse response = SqlExecutionConverter.toResponse(result);
                    if (response != null) {
                        response.setDatabaseName(db.catalog());
                        response.setSchemaName(db.schema());
                    }
                    responses.add(response);

                    if (!result.isSuccess()) {
                        connection.rollback();
                        markBatchRolledBack(responses, sqls, i, db);
                        return responses;
                    }
                }
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection, e);
                log.warn("Batch SQL execution failed and was rolled back: {}", e.getMessage());
                addBatchExceptionResponses(responses, sqls, db, e);
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }
        }
        return responses;
    }

    private boolean getAutoCommit(Connection connection) {
        try {
            return connection.getAutoCommit();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read connection auto-commit state", e);
        }
    }

    private void rollbackQuietly(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            original.addSuppressed(rollbackException);
            log.warn("Failed to roll back batch transaction", rollbackException);
        }
    }

    private void restoreAutoCommit(Connection connection, boolean originalAutoCommit) {
        try {
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException e) {
            log.warn("Failed to restore connection auto-commit state", e);
        }
    }

    private void markBatchRolledBack(List<ExecuteSqlResponse> responses, List<String> sqls,
                                     int failedIndex, DbContext db) {
        String message = "Batch transaction rolled back because statement " + (failedIndex + 1) + " failed";
        for (int i = 0; i < responses.size(); i++) {
            ExecuteSqlResponse response = responses.get(i);
            if (response == null) {
                responses.set(i, failedResponse(sqls.get(i), db, message));
            } else if (i < failedIndex) {
                response.setSuccess(false);
                response.setErrorMessage(message);
            } else {
                String databaseError = response.getErrorMessage();
                response.setErrorMessage(databaseError == null ? message : databaseError + "; " + message);
            }
        }
        for (int i = failedIndex + 1; i < sqls.size(); i++) {
            responses.add(failedResponse(sqls.get(i), db,
                    "Not executed because the batch transaction was rolled back"));
        }
    }

    private void addBatchExceptionResponses(List<ExecuteSqlResponse> responses, List<String> sqls,
                                            DbContext db, Exception exception) {
        String message = "Batch transaction rolled back: " + exception.getMessage();
        for (ExecuteSqlResponse response : responses) {
            if (response != null) {
                response.setSuccess(false);
                response.setErrorMessage(message);
            }
        }
        for (int i = responses.size(); i < sqls.size(); i++) {
            responses.add(failedResponse(sqls.get(i), db, message));
        }
    }

    private ExecuteSqlResponse failedResponse(String sql, DbContext db, String message) {
        return ExecuteSqlResponse.builder()
                .success(false)
                .errorMessage(message)
                .originalSql(sql)
                .databaseName(db.catalog())
                .schemaName(db.schema())
                .build();
    }

    private boolean isTransactionControl(String sql) {
        if (sql == null) {
            return false;
        }
        String normalized = sql.stripLeading();
        while (normalized.startsWith("--") || normalized.startsWith("/*")) {
            if (normalized.startsWith("--")) {
                int newline = normalized.indexOf('\n');
                normalized = newline < 0 ? "" : normalized.substring(newline + 1).stripLeading();
            } else {
                int end = normalized.indexOf("*/");
                normalized = end < 0 ? "" : normalized.substring(end + 2).stripLeading();
            }
        }
        return normalized.matches("(?is)^(COMMIT|ROLLBACK)\\b.*")
                || normalized.matches("(?is)^START\\s+TRANSACTION\\b.*")
                || normalized.matches("(?is)^BEGIN(?:\\s*;|\\s+(?:TRANSACTION|WORK)\\b.*|\\s*)$");
    }
}
