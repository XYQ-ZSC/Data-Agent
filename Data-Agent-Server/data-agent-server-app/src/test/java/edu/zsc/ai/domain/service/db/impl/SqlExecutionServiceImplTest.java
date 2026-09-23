package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.AgentExecuteSqlRequest;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.service.db.ConnectionAccessService;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.plugin.capability.CommandExecutor;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandRequest;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlExecutionServiceImplTest {

    @Test
    void singleStatementUsesExecutorTransaction() throws Exception {
        TestContext context = context();
        SqlCommandResult success = result(true, null);
        when(context.executor.executeCommand(org.mockito.ArgumentMatchers.any())).thenReturn(success);

        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            context.service.executeSql(AgentExecuteSqlRequest.builder()
                    .connectionId(context.db.connectionId())
                    .catalog(context.db.catalog())
                    .schema(context.db.schema())
                    .sql("UPDATE T SET C = 1")
                    .build());
        }

        ArgumentCaptor<SqlCommandRequest> request = ArgumentCaptor.forClass(SqlCommandRequest.class);
        verify(context.executor).executeCommand(request.capture());
        assertTrue(request.getValue().isNeedTransaction());
    }

    @Test
    void batchRollsBackAllStatementsWhenOneFails() throws Exception {
        TestContext context = context();
        when(context.connection.getAutoCommit()).thenReturn(true);
        AtomicInteger invocation = new AtomicInteger();
        when(context.executor.executeCommand(org.mockito.ArgumentMatchers.any())).thenAnswer(ignored ->
                invocation.getAndIncrement() == 0 ? result(true, null) : result(false, "broken statement"));

        List<ExecuteSqlResponse> responses;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            responses = context.service.executeBatchSql(context.db, List.of("UPDATE A", "UPDATE B", "UPDATE C"));
        }

        verify(context.connection).setAutoCommit(false);
        verify(context.connection).rollback();
        verify(context.connection, never()).commit();
        verify(context.connection).setAutoCommit(true);
        assertTrue(responses.stream().allMatch(response -> !response.isSuccess()));
        assertTrue(responses.get(0).getErrorMessage().contains("rolled back"));
        assertFalse(responses.get(2).isSuccess());
    }

    @Test
    void rejectsCrossRequestTransactionControl() {
        ConnectionService connectionService = mock(ConnectionService.class);
        SqlExecutionServiceImpl service = new SqlExecutionServiceImpl(
                connectionService, mock(ConnectionAccessService.class));
        AgentExecuteSqlRequest request = AgentExecuteSqlRequest.builder()
                .connectionId(1L)
                .catalog("DB")
                .schema("SCHEMA")
                .sql("BEGIN")
                .build();

        ExecuteSqlResponse response = service.executeSql(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("one batch"));
        verify(connectionService, never()).openConnection(org.mockito.ArgumentMatchers.any(DbContext.class));
    }

    @SuppressWarnings("unchecked")
    private TestContext context() throws Exception {
        ConnectionService connectionService = mock(ConnectionService.class);
        ConnectionAccessService accessService = mock(ConnectionAccessService.class);
        SqlExecutionServiceImpl service = new SqlExecutionServiceImpl(connectionService, accessService);
        DbContext db = new DbContext(1L, "DB", "SCHEMA");
        Connection connection = mock(Connection.class);
        ActiveConnectionRegistry.ActiveConnection active = mock(ActiveConnectionRegistry.ActiveConnection.class);
        when(active.pluginId()).thenReturn("test-plugin");
        when(active.borrowConnection()).thenReturn(new ActiveConnectionRegistry.BorrowedConnection(connection, active));
        CommandExecutor<SqlCommandRequest, SqlCommandResult> executor = mock(CommandExecutor.class);
        DefaultPluginManager pluginManager = mock(DefaultPluginManager.class);
        when(pluginManager.getSqlCommandExecutorByPluginId("test-plugin")).thenReturn(executor);
        return new TestContext(service, db, connection, active, executor, pluginManager);
    }

    private SqlCommandResult result(boolean success, String error) {
        SqlCommandResult result = new SqlCommandResult();
        result.setSuccess(success);
        result.setErrorMessage(error);
        return result;
    }

    private record TestContext(
            SqlExecutionServiceImpl service,
            DbContext db,
            Connection connection,
            ActiveConnectionRegistry.ActiveConnection active,
            CommandExecutor<SqlCommandRequest, SqlCommandResult> executor,
            DefaultPluginManager pluginManager) {
    }
}
