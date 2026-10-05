import http from '../lib/http';
import { SqlPaths } from '../constants/apiPaths';
import type { SqlCompletionParams, SqlCompletionResponse } from '../types/sql';

export const sqlCompletionService = {
  complete: async (params: SqlCompletionParams, signal?: AbortSignal): Promise<SqlCompletionResponse> => {
    const response = await http.post<SqlCompletionResponse>(SqlPaths.COMPLETE, {
      connectionId: params.connectionId,
      catalog: params.databaseName || undefined,
      schema: params.schemaName || undefined,
      sql: params.sql,
      cursorOffset: params.cursorOffset,
    }, { signal });
    return response.data;
  },
  dictionary: async (connectionId: number, signal?: AbortSignal): Promise<SqlCompletionResponse> => {
    const response = await http.get<SqlCompletionResponse>(SqlPaths.COMPLETION_DICTIONARY, { params: { connectionId }, signal });
    return response.data;
  },
  invalidate: async (connectionId: number) => {
    await http.post(SqlPaths.COMPLETION_INVALIDATE, null, { params: { connectionId } });
  },
};
