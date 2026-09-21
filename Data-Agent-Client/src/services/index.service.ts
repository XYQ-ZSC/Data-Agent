import http from '../lib/http';
import { ApiPaths } from '../constants/apiPaths';

export interface IndexMetadata {
  name: string;
  type: string;
  columns: string[];
  unique: boolean;
  isPrimaryKey?: boolean;
}

export const indexService = {
  listIndexes: async (
    connectionId: string,
    tableName: string,
    catalog?: string,
    schema?: string
  ): Promise<IndexMetadata[]> => {
    const params: Record<string, string> = { connectionId, tableName };
    if (catalog != null && catalog !== '') params.catalog = catalog;
    if (schema != null && schema !== '') params.schema = schema;

    const response = await http.get<IndexMetadata[]>(ApiPaths.INDEXES, { params });
    return response.data;
  },
};
