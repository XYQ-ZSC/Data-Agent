package edu.zsc.ai.api.controller.db;

import edu.zsc.ai.domain.model.dto.request.db.AgentExecuteSqlRequest;
import edu.zsc.ai.domain.model.dto.request.db.ExecuteSqlRequest;
import edu.zsc.ai.domain.model.dto.request.db.ExecuteSqlBatchRequest;
import edu.zsc.ai.domain.model.dto.request.db.CancelSqlRequest;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.service.db.SqlExecutionService;
import edu.zsc.ai.domain.service.db.SqlExecutionOptions;
import edu.zsc.ai.plugin.execution.StatementCancellationRegistry.CancelOutcome;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.ConnectionAccessService;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.transaction.BatchMode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/db/sql")
@RequiredArgsConstructor
public class SqlExecutionController {

    private final SqlExecutionService sqlExecutionService;
    private final ConnectionService connectionService;
    private final ConnectionAccessService connectionAccessService;

    @PostMapping("/execute")
    public ApiResponse<ExecuteSqlResponse> executeSql(@Valid @RequestBody ExecuteSqlRequest request) {
        log.info("Executing SQL: connectionId={}, catalog={}, schema={}",
                request.getConnectionId(), request.getCatalog(), request.getSchema());

        AgentExecuteSqlRequest agentRequest = AgentExecuteSqlRequest.builder()
                .conversationId(request.getConversationId())
                .connectionId(request.getConnectionId())
                .catalog(request.getCatalog())
                .schema(request.getSchema())
                .sql(request.getSql())
                .timeoutMs(request.getTimeoutMs())
                .maxRows(request.getMaxRows())
                .executionId(request.getExecutionId())
                .build();

        ExecuteSqlResponse response = sqlExecutionService.executeSql(agentRequest);
        return ApiResponse.success(response);
    }

    @PostMapping("/execute-script")
    public ApiResponse<List<ExecuteSqlResponse>> executeScript(@Valid @RequestBody ExecuteSqlBatchRequest request) {
        connectionAccessService.assertWorkbenchApiAllowed();
        DbContext db = DbContext.from(request);
        connectionService.openConnection(db);
        String pluginId = ActiveConnectionRegistry.getOwnedConnection(db).pluginId();
        List<String> statements = DefaultPluginManager.getInstance()
                .getSqlSplitterByPluginId(pluginId).split(request.getSql());
        BatchMode mode = request.getMode() == null ? BatchMode.ATOMIC : request.getMode();
        return ApiResponse.success(sqlExecutionService.executeBatchSql(db, statements, mode,
                new SqlExecutionOptions(request.getTimeoutMs(), request.getMaxRows(), request.getExecutionId())));
    }

    @PostMapping("/cancel")
    public ApiResponse<CancelOutcome> cancel(@Valid @RequestBody CancelSqlRequest request) {
        return ApiResponse.success(sqlExecutionService.cancelExecution(
                DbContext.from(request), request.getExecutionId()));
    }
}
