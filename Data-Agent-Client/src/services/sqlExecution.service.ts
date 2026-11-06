import http from '../lib/http';
import type { ExecuteSqlResponse, ExecuteSqlParams, ExecuteSqlScriptParams } from '../types/sql';
import { SqlPaths } from '../constants/apiPaths';

interface ExecuteSqlApiPayload {
  connectionId: number;
  catalog?: string;
  schema?: string;
  sql: string;
  timeoutMs?: number;
  maxRows?: number;
  executionId?: string;
}

function toOptionalScope(value?: string | null): string | undefined {
  return value != null && value !== '' ? value : undefined;
}

export const sqlExecutionService = {
  /**
   * Execute SQL on a specific connection and database
   * @param params - ExecuteSqlParams containing connectionId, database, schema, and SQL
   * @returns ExecuteSqlResponse with results or error
   */
  executeSql: async (params: ExecuteSqlParams): Promise<ExecuteSqlResponse> => {
    const payload: ExecuteSqlApiPayload = {
      connectionId: params.connectionId,
      catalog: toOptionalScope(params.databaseName),
      schema: toOptionalScope(params.schemaName),
      sql: params.sql,
      timeoutMs: params.timeoutMs,
      maxRows: params.maxRows,
      executionId: params.executionId,
    };
    const response = await http.post<ExecuteSqlResponse>(SqlPaths.EXECUTE, payload);
    return response.data;
  },
  executeScript: async (params: ExecuteSqlScriptParams): Promise<ExecuteSqlResponse[]> => {
    const response = await http.post<ExecuteSqlResponse[]>(SqlPaths.EXECUTE_SCRIPT, {
      connectionId: params.connectionId,
      catalog: toOptionalScope(params.databaseName),
      schema: toOptionalScope(params.schemaName),
      sql: params.sql,
      mode: params.mode,
      timeoutMs: params.timeoutMs,
      maxRows: params.maxRows,
      executionId: params.executionId,
    }, { timeout: 0 });
    return response.data;
  },
  cancelExecution: async (params: Pick<ExecuteSqlParams, 'connectionId' | 'databaseName' | 'schemaName' | 'executionId'>): Promise<'NO_ACTIVE_STATEMENT' | 'CANCEL_REQUESTED' | 'CANCEL_FAILED'> => {
    const response = await http.post<'NO_ACTIVE_STATEMENT' | 'CANCEL_REQUESTED' | 'CANCEL_FAILED'>(SqlPaths.CANCEL, {
      connectionId: params.connectionId,
      catalog: toOptionalScope(params.databaseName),
      schema: toOptionalScope(params.schemaName),
      executionId: params.executionId,
    });
    return response.data;
  },
};
