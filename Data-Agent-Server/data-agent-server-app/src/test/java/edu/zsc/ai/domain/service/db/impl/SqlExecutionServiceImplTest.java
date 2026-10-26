package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.AgentExecuteSqlRequest;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.service.db.ConnectionAccessService;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.SqlExecutionOptions;
import edu.zsc.ai.context.RequestContext;
import edu.zsc.ai.context.RequestContextInfo;
import edu.zsc.ai.common.enums.org.WorkspaceTypeEnum;
import edu.zsc.ai.plugin.execution.StatementCancellationRegistry;
import edu.zsc.ai.plugin.capability.CommandExecutor;
import edu.zsc.ai.plugin.capability.SqlValidator;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandRequest;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.sql.SqlType;
import edu.zsc.ai.plugin.model.sql.SqlValidationResult;
import edu.zsc.ai.plugin.model.transaction.BatchExecutionState;
import edu.zsc.ai.plugin.model.transaction.BatchMode;
import edu.zsc.ai.plugin.model.transaction.StatementExecutionState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlExecutionServiceImplTest {

    @Test
    void cancelOnlyReachesStatementForSameUserWorkspaceAndConnection() throws Exception {
        TestContext context = context();
        String executionId = UUID.randomUUID().toString();
        Statement statement = mock(Statement.class);
        when(context.validator.validate("SELECT 1"))
                .thenReturn(SqlValidationResult.valid(SqlType.SELECT, List.of(), List.of()));
        when(context.executor.executeCommand(any())).thenAnswer(ignored -> {
            try (var registration = StatementCancellationRegistry.getInstance().register(executionId, statement)) {
                RequestContext.set(RequestContextInfo.builder().userId(2L)
                        .workspaceType(WorkspaceTypeEnum.PERSONAL).build());
                assertEquals(StatementCancellationRegistry.CancelOutcome.NO_ACTIVE_STATEMENT,
                        context.service.cancelExecution(context.db, executionId));
                RequestContext.set(RequestContextInfo.builder().userId(1L)
                        .workspaceType(WorkspaceTypeEnum.ORGANIZATION).orgId(7L).build());
                assertEquals(StatementCancellationRegistry.CancelOutcome.NO_ACTIVE_STATEMENT,
                        context.service.cancelExecution(context.db, executionId));
                RequestContext.set(RequestContextInfo.builder().userId(1L)
                        .workspaceType(WorkspaceTypeEnum.PERSONAL).build());
                assertEquals(StatementCancellationRegistry.CancelOutcome.NO_ACTIVE_STATEMENT,
                        context.service.cancelExecution(new DbContext(2L, "DB", "SCHEMA"), executionId));
                assertEquals(StatementCancellationRegistry.CancelOutcome.CANCEL_REQUESTED,
                        context.service.cancelExecution(context.db, executionId));
            }
            return result(true, null);
        });

        RequestContext.set(RequestContextInfo.builder().userId(1L)
                .workspaceType(WorkspaceTypeEnum.PERSONAL).build());
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);
            context.service.executeSql(AgentExecuteSqlRequest.builder()
                    .connectionId(context.db.connectionId()).catalog(context.db.catalog())
                    .schema(context.db.schema()).sql("SELECT 1").executionId(executionId).build());
        } finally {
            RequestContext.clear();
        }
        verify(statement).cancel();
        assertEquals(StatementCancellationRegistry.CancelOutcome.NO_ACTIVE_STATEMENT,
                context.service.cancelExecution(context.db, executionId));
    }

    @Test
    void writeStatementKeepsExecutorTransaction() throws Exception {
        TestContext context = context();
        when(context.validator.validate("UPDATE T SET C = 1"))
                .thenReturn(SqlValidationResult.valid(SqlType.UPDATE, List.of(), List.of()));
        when(context.executor.executeCommand(any())).thenReturn(result(true, null));

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
    void readOnlyStatementSkipsTransaction() throws Exception {
        TestContext context = context();
        when(context.validator.validate("SELECT 1"))
                .thenReturn(SqlValidationResult.valid(SqlType.SELECT, List.of(), List.of()));
        when(context.executor.executeCommand(any())).thenReturn(result(true, null));

        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            context.service.executeSql(AgentExecuteSqlRequest.builder()
                    .connectionId(context.db.connectionId())
                    .catalog(context.db.catalog())
                    .schema(context.db.schema())
                    .sql("SELECT 1")
                    .build());
        }

        ArgumentCaptor<SqlCommandRequest> request = ArgumentCaptor.forClass(SqlCommandRequest.class);
        verify(context.executor).executeCommand(request.capture());
        assertFalse(request.getValue().isNeedTransaction());
    }

    @Test
    void unclassifiableStatementKeepsTransaction() throws Exception {
        TestContext context = context();
        when(context.validator.validate("STRANGE SQL"))
                .thenReturn(SqlValidationResult.invalid(SqlType.UNKNOWN, List.of()));
        when(context.executor.executeCommand(any())).thenReturn(result(true, null));

        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            context.service.executeSql(AgentExecuteSqlRequest.builder()
                    .connectionId(context.db.connectionId())
                    .catalog(context.db.catalog())
                    .schema(context.db.schema())
                    .sql("STRANGE SQL")
                    .build());
        }

        ArgumentCaptor<SqlCommandRequest> request = ArgumentCaptor.forClass(SqlCommandRequest.class);
        verify(context.executor).executeCommand(request.capture());
        assertTrue(request.getValue().isNeedTransaction());
    }

    @Test
    void rejectsTransactionControlViaPluginValidator() throws Exception {
        TestContext context = context();
        when(context.validator.isTransactionControl("BEGIN")).thenReturn(true);

        ExecuteSqlResponse response;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            response = context.service.executeSql(AgentExecuteSqlRequest.builder()
                    .connectionId(context.db.connectionId())
                    .catalog(context.db.catalog())
                    .schema(context.db.schema())
                    .sql("BEGIN")
                    .build());
        }

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("one batch"));
        assertEquals(StatementExecutionState.NOT_EXECUTED, response.getStatementState());
        verify(context.executor, never()).executeCommand(any());
    }

    @Test
    void atomicBatchFailureMarksStatesWithoutRewritingFacts() throws Exception {
        TestContext context = context();
        when(context.connection.getAutoCommit()).thenReturn(true);
        AtomicInteger invocation = new AtomicInteger();
        when(context.executor.executeCommand(any())).thenAnswer(ignored ->
                invocation.getAndIncrement() == 0 ? result(true, null) : failedResult("broken statement"));

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
        // the successfully executed statement keeps its factual success flag and original message
        assertTrue(responses.get(0).isSuccess());
        assertEquals(StatementExecutionState.ROLLED_BACK, responses.get(0).getStatementState());
        assertNull(responses.get(0).getErrorMessage());
        // the failed statement keeps its own error
        assertFalse(responses.get(1).isSuccess());
        assertEquals(StatementExecutionState.FAILED, responses.get(1).getStatementState());
        assertTrue(responses.get(1).getErrorMessage().contains("broken statement"));
        // the skipped statement was never submitted
        assertFalse(responses.get(2).isSuccess());
        assertEquals(StatementExecutionState.NOT_EXECUTED, responses.get(2).getStatementState());
        responses.forEach(r -> assertEquals(BatchExecutionState.ROLLED_BACK, r.getBatchState()));
    }

    @Test
    void atomicBatchRejectsDdlBeforeAnyExecution() throws Exception {
        TestContext context = context();

        List<ExecuteSqlResponse> responses;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            responses = context.service.executeBatchSql(context.db,
                    List.of("CREATE TABLE X (ID INT)", "INSERT INTO X VALUES (1)", "INSERT INTO NOPE VALUES (1)"));
        }

        verify(context.executor, never()).executeCommand(any());
        verify(context.connection, never()).setAutoCommit(false);
        responses.forEach(r -> {
            assertEquals(StatementExecutionState.NOT_EXECUTED, r.getStatementState());
            assertEquals(BatchExecutionState.FAILED, r.getBatchState());
            assertTrue(r.getErrorMessage().contains("STEPWISE"));
        });
    }

    @Test
    void stepwiseModeAllowsExplicitDdlAndReportsIndependentCommit() throws Exception {
        TestContext context = context();
        SqlCommandResult committed = result(true, null);
        committed.setStatementState(StatementExecutionState.COMMITTED);
        committed.setTransactionOutcome(edu.zsc.ai.plugin.model.transaction.TransactionOutcome.COMMITTED);
        when(context.executor.executeCommand(any())).thenReturn(committed);

        List<ExecuteSqlResponse> responses;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);
            responses = context.service.executeBatchSql(context.db,
                    List.of("CREATE TABLE X (ID INT)"), BatchMode.STEPWISE);
        }

        assertEquals(StatementExecutionState.COMMITTED, responses.get(0).getStatementState());
        assertEquals(BatchExecutionState.SUCCESS, responses.get(0).getBatchState());
        verify(context.executor).executeCommand(any());
    }

    @Test
    void atomicBatchCommitFailureIsNotReportedAsCommitted() throws Exception {
        TestContext context = context();
        when(context.connection.getAutoCommit()).thenReturn(true);
        when(context.executor.executeCommand(any())).thenReturn(result(true, null));
        doThrow(new SQLException("commit lost")).when(context.connection).commit();

        List<ExecuteSqlResponse> responses;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            responses = context.service.executeBatchSql(context.db, List.of("UPDATE A", "UPDATE B"));
        }

        // commit failed -> rollback succeeded -> ROLLED_BACK, never COMMITTED
        responses.forEach(r -> {
            assertEquals(StatementExecutionState.ROLLED_BACK, r.getStatementState());
            assertEquals(BatchExecutionState.ROLLED_BACK, r.getBatchState());
        });
    }

    @Test
    void atomicBatchCommitAndRollbackFailureIsUnknown() throws Exception {
        TestContext context = context();
        when(context.connection.getAutoCommit()).thenReturn(true);
        when(context.executor.executeCommand(any())).thenReturn(result(true, null));
        doThrow(new SQLException("commit lost")).when(context.connection).commit();
        doThrow(new SQLException("rollback lost")).when(context.connection).rollback();

        List<ExecuteSqlResponse> responses;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            responses = context.service.executeBatchSql(context.db, List.of("UPDATE A"));
        }

        assertEquals(StatementExecutionState.UNKNOWN, responses.get(0).getStatementState());
        assertEquals(BatchExecutionState.UNKNOWN, responses.get(0).getBatchState());
    }

    @Test
    void stepwiseBatchCommitsIndependentlyAndContinues() throws Exception {
        TestContext context = context();
        AtomicInteger invocation = new AtomicInteger();
        when(context.executor.executeCommand(any())).thenAnswer(ignored -> {
            int index = invocation.getAndIncrement();
            if (index == 1) {
                return failedResult("broken statement");
            }
            SqlCommandResult committed = result(true, null);
            committed.setStatementState(StatementExecutionState.COMMITTED);
            committed.setTransactionOutcome(edu.zsc.ai.plugin.model.transaction.TransactionOutcome.COMMITTED);
            return committed;
        });

        List<ExecuteSqlResponse> responses;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            responses = context.service.executeBatchSql(context.db,
                    List.of("UPDATE A", "UPDATE B", "UPDATE C"), BatchMode.STEPWISE);
        }

        assertEquals(3, responses.size());
        verify(context.executor, org.mockito.Mockito.times(3)).executeCommand(any());
        verify(context.connection, never()).commit();
        verify(context.connection, never()).rollback();
        assertEquals(StatementExecutionState.COMMITTED, responses.get(0).getStatementState());
        assertEquals(StatementExecutionState.FAILED, responses.get(1).getStatementState());
        assertEquals(StatementExecutionState.COMMITTED, responses.get(2).getStatementState());
        responses.forEach(r -> assertEquals(BatchExecutionState.PARTIAL, r.getBatchState()));
        // per-statement requests are independently transactional
        ArgumentCaptor<SqlCommandRequest> requests = ArgumentCaptor.forClass(SqlCommandRequest.class);
        verify(context.executor, org.mockito.Mockito.times(3)).executeCommand(requests.capture());
        requests.getAllValues().forEach(r -> assertTrue(r.isNeedTransaction()));
    }

    @Test
    void stepwiseCancellationStopsRemainingStatementsAndForwardsLimits() throws Exception {
        TestContext context = context();
        SqlCommandResult cancelled = failedResult("cancelled by database");
        cancelled.setStatementState(StatementExecutionState.CANCELLED);
        when(context.executor.executeCommand(any())).thenReturn(cancelled);

        List<ExecuteSqlResponse> responses;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            responses = context.service.executeBatchSql(context.db, List.of("SELECT 1", "UPDATE T SET C=1"),
                    BatchMode.STEPWISE, new SqlExecutionOptions(5000, 50, null));
        }

        ArgumentCaptor<SqlCommandRequest> requests = ArgumentCaptor.forClass(SqlCommandRequest.class);
        verify(context.executor).executeCommand(requests.capture());
        assertEquals(5000, requests.getValue().getTimeoutMs());
        assertEquals(50, requests.getValue().getMaxRows());
        assertEquals(StatementExecutionState.CANCELLED, responses.get(0).getStatementState());
        assertEquals(StatementExecutionState.NOT_EXECUTED, responses.get(1).getStatementState());
    }

    @Test
    void cancelBetweenStatementsPreventsNextStepFromStarting() throws Exception {
        TestContext context = context();
        String executionId = UUID.randomUUID().toString();
        when(context.executor.executeCommand(any())).thenAnswer(ignored -> {
            assertEquals(StatementCancellationRegistry.CancelOutcome.CANCEL_REQUESTED,
                    context.service.cancelExecution(context.db, executionId));
            SqlCommandResult committed = result(true, null);
            committed.setStatementState(StatementExecutionState.COMMITTED);
            committed.setTransactionOutcome(edu.zsc.ai.plugin.model.transaction.TransactionOutcome.COMMITTED);
            return committed;
        });
        RequestContext.set(RequestContextInfo.builder().userId(1L)
                .workspaceType(WorkspaceTypeEnum.PERSONAL).build());
        List<ExecuteSqlResponse> responses;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);
            responses = context.service.executeBatchSql(context.db, List.of("SELECT 1", "UPDATE T SET C=1"),
                    BatchMode.STEPWISE, new SqlExecutionOptions(5000, 50, executionId));
        } finally {
            RequestContext.clear();
        }
        verify(context.executor).executeCommand(any());
        assertEquals(StatementExecutionState.COMMITTED, responses.get(0).getStatementState());
        assertEquals(StatementExecutionState.NOT_EXECUTED, responses.get(1).getStatementState());
    }

    @Test
    void batchWithTransactionControlIsRejectedBeforeExecution() throws Exception {
        TestContext context = context();
        when(context.validator.isTransactionControl("COMMIT")).thenReturn(true);

        List<ExecuteSqlResponse> responses;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            responses = context.service.executeBatchSql(context.db, List.of("UPDATE A", "COMMIT"));
        }

        assertEquals(2, responses.size());
        responses.forEach(r -> {
            assertFalse(r.isSuccess());
            assertEquals(StatementExecutionState.NOT_EXECUTED, r.getStatementState());
            assertEquals(BatchExecutionState.FAILED, r.getBatchState());
            assertTrue(r.getErrorMessage().contains("already transactional"));
        });
        verify(context.executor, never()).executeCommand(any());
    }

    @Test
    void successfulDdlInvalidatesCompletionMetadata() throws Exception {
        TestContext context = context();
        when(context.validator.classifySql("ALTER TABLE T ADD C INT")).thenReturn(SqlType.ALTER);
        when(context.executor.executeCommand(any())).thenReturn(result(true, null));
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);
            context.service.executeSql(AgentExecuteSqlRequest.builder().connectionId(context.db.connectionId())
                    .catalog(context.db.catalog()).schema(context.db.schema()).sql("ALTER TABLE T ADD C INT").build());
        }
        verify(context.completionCache).invalidate(1L);
    }

    @Test
    void failedDdlDoesNotInvalidateCompletionMetadata() throws Exception {
        TestContext context = context();
        when(context.validator.classifySql("CREATE TABLE T(ID INT)")).thenReturn(SqlType.CREATE);
        when(context.executor.executeCommand(any())).thenReturn(failedResult("denied"));
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);
            context.service.executeSql(AgentExecuteSqlRequest.builder().connectionId(context.db.connectionId())
                    .catalog(context.db.catalog()).schema(context.db.schema()).sql("CREATE TABLE T(ID INT)").build());
        }
        verify(context.completionCache, never()).invalidate(any());
    }

    @Test
    void partialStepwiseBatchInvalidatesWhenLaterDdlSucceeded() throws Exception {
        TestContext context = context();
        when(context.validator.classifySql("BAD SQL")).thenReturn(SqlType.UNKNOWN);
        when(context.validator.classifySql("CREATE TABLE T(ID INT)")).thenReturn(SqlType.CREATE);
        SqlCommandResult ddl = result(true, null);
        ddl.setStatementState(StatementExecutionState.COMMITTED);
        when(context.executor.executeCommand(any())).thenReturn(failedResult("bad SQL"), ddl);
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);
            context.service.executeBatchSql(context.db, List.of("BAD SQL", "CREATE TABLE T(ID INT)"), BatchMode.STEPWISE);
        }
        verify(context.completionCache).invalidate(1L);
    }

    @SuppressWarnings("unchecked")
    private TestContext context() throws Exception {
        ConnectionService connectionService = mock(ConnectionService.class);
        ConnectionAccessService accessService = mock(ConnectionAccessService.class);
        SqlCompletionMetadataCache completionCache = mock(SqlCompletionMetadataCache.class);
        SqlExecutionServiceImpl service = new SqlExecutionServiceImpl(connectionService, accessService, completionCache);
        DbContext db = new DbContext(1L, "DB", "SCHEMA");
        Connection connection = mock(Connection.class);
        ActiveConnectionRegistry.ActiveConnection active = mock(ActiveConnectionRegistry.ActiveConnection.class);
        when(active.pluginId()).thenReturn("test-plugin");
        when(active.borrowConnection()).thenReturn(new ActiveConnectionRegistry.BorrowedConnection(connection, active));
        CommandExecutor<SqlCommandRequest, SqlCommandResult> executor = mock(CommandExecutor.class);
        SqlValidator validator = mock(SqlValidator.class);
        when(validator.validate(any())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            SqlType type = sql.startsWith("SELECT") ? SqlType.SELECT
                    : sql.startsWith("INSERT") ? SqlType.INSERT
                    : sql.startsWith("UPDATE") ? SqlType.UPDATE
                    : SqlType.UNKNOWN;
            return SqlValidationResult.valid(type, List.of(), List.of());
        });
        DefaultPluginManager pluginManager = mock(DefaultPluginManager.class);
        when(pluginManager.getSqlCommandExecutorByPluginId("test-plugin")).thenReturn(executor);
        when(pluginManager.getSqlValidatorByPluginId("test-plugin")).thenReturn(validator);
        return new TestContext(service, db, connection, active, executor, validator, pluginManager, completionCache);
    }

    private SqlCommandResult result(boolean success, String error) {
        SqlCommandResult result = new SqlCommandResult();
        result.setSuccess(success);
        result.setErrorMessage(error);
        return result;
    }

    private SqlCommandResult failedResult(String error) {
        SqlCommandResult result = result(false, error);
        result.setStatementState(StatementExecutionState.FAILED);
        result.setTransactionOutcome(edu.zsc.ai.plugin.model.transaction.TransactionOutcome.NONE);
        return result;
    }

    private record TestContext(
            SqlExecutionServiceImpl service,
            DbContext db,
            Connection connection,
            ActiveConnectionRegistry.ActiveConnection active,
            CommandExecutor<SqlCommandRequest, SqlCommandResult> executor,
            SqlValidator validator,
            DefaultPluginManager pluginManager,
            SqlCompletionMetadataCache completionCache) {
    }
}
