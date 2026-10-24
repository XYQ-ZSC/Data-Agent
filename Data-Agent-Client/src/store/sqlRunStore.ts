import { create } from 'zustand';
import type { ExecuteSqlResponse, SqlBatchMode } from '../types/sql';
import { sqlExecutionService } from '../services/sqlExecution.service';
import { sqlAnalysisService } from '../services/sqlAnalysis.service';
import { EXPLORER_SQL_OBJECT_CHANGED } from '../constants/explorer';

/**
 * Shared SQL execution state, decoupled from the query-console tab that happens
 * to be active. Lets both the SQL editor (Home) and the "Run SQL Script" dialog
 * run a script and stream its outcome into the workspace Output/Results panel.
 */
let currentExecutionId: string | null = null;

export interface RunSqlParams {
  connectionId: number;
  databaseName?: string | null;
  schemaName?: string | null;
  /** DB type code (e.g. 'DM'); used for post-run DDL object-change analysis. */
  dbType?: string | null;
  sql: string;
  mode?: SqlBatchMode;
  timeoutMs?: number;
  maxRows?: number;
}

interface StopSqlParams {
  connectionId: number;
  databaseName?: string | null;
  schemaName?: string | null;
}

interface SqlRunState {
  executeResult: ExecuteSqlResponse | null;
  scriptResults: ExecuteSqlResponse[];
  isRunning: boolean;
  isResultsVisible: boolean;
  cancelStatus: string | null;
  runSql: (params: RunSqlParams) => Promise<void>;
  stopSql: (params: StopSqlParams) => Promise<void>;
  setResultsVisible: (visible: boolean) => void;
}

export const useSqlRunStore = create<SqlRunState>((set) => ({
  executeResult: null,
  scriptResults: [],
  isRunning: false,
  isResultsVisible: false,
  cancelStatus: null,

  runSql: async (params) => {
    const { connectionId, databaseName, schemaName, dbType, sql, mode, timeoutMs, maxRows } = params;
    if (!sql || !connectionId) return;

    const executionId = crypto.randomUUID();
    currentExecutionId = executionId;
    set({
      isRunning: true,
      cancelStatus: null,
      executeResult: null,
      scriptResults: [],
      isResultsVisible: true,
    });

    try {
      const results = await sqlExecutionService.executeScript({
        connectionId,
        databaseName: databaseName ?? undefined,
        schemaName: schemaName ?? undefined,
        sql,
        mode: mode ?? 'ATOMIC',
        timeoutMs,
        maxRows,
        executionId,
      });

      // A newer run has superseded this one; discard its stale outcome so it
      // does not overwrite the newer run's state.
      if (currentExecutionId !== executionId) return;

      set({ scriptResults: results });

      const result = results[0];
      if (!result) throw new Error('The script contained no executable statements');

      set({
        executeResult: {
          ...result,
          originalSql: result.originalSql || sql,
          executedSql: result.executedSql || sql,
        },
      });

      // After successful DDL on a DM database, refresh the explorer tree scope.
      if (results.some((item) => item.success) && dbType?.toUpperCase() === 'DM') {
        sqlAnalysisService
          .analyze({
            connectionId,
            databaseName: databaseName ?? undefined,
            schemaName: schemaName ?? undefined,
            sql,
          })
          .then((analysis) => {
            if (analysis.errors.length) return;
            for (const [index, statement] of analysis.statements.entries()) {
              if (
                results[index]?.success &&
                ['CREATE', 'ALTER', 'DROP', 'TRUNCATE'].includes(statement.type) &&
                statement.objectType
              ) {
                window.dispatchEvent(
                  new CustomEvent(EXPLORER_SQL_OBJECT_CHANGED, {
                    detail: {
                      connectionId,
                      catalog: databaseName,
                      schema: schemaName,
                      objectType: statement.objectType,
                    },
                  })
                );
              }
            }
          })
          .catch(() => undefined);
      }
    } catch (err: unknown) {
      if (currentExecutionId !== executionId) return;
      set({
        executeResult: {
          success: false,
          errorMessage: String(err),
          executionTimeMs: 0,
          originalSql: sql,
          executedSql: sql,
          query: false,
          affectedRows: 0,
        },
      });
    } finally {
      // Only the latest run may clear the shared running state.
      if (currentExecutionId === executionId) {
        currentExecutionId = null;
        set({ isRunning: false });
      }
    }
  },

  stopSql: async (params) => {
    if (!currentExecutionId || !params.connectionId) return;
    try {
      const outcome = await sqlExecutionService.cancelExecution({
        connectionId: params.connectionId,
        databaseName: params.databaseName ?? undefined,
        schemaName: params.schemaName ?? undefined,
        executionId: currentExecutionId,
      });
      set({
        cancelStatus:
          outcome === 'CANCEL_REQUESTED'
            ? 'Cancel requested; waiting for database confirmation'
            : outcome === 'CANCEL_FAILED'
              ? 'Cancel request failed; query may still be running'
              : 'No active database statement to cancel',
      });
    } catch (error) {
      set({ cancelStatus: `Cancel request failed: ${String(error)}` });
    }
  },

  setResultsVisible: (visible) => set({ isResultsVisible: visible }),
}));
