import http from '../lib/http';
import { ApiPaths } from '../constants/apiPaths';

export interface PrimaryKeyMetadata {
  name: string;
  columnNames: string[];
}

export const primaryKeyService = {
  listPrimaryKeys: async (
    connectionId: string,
    tableName: string,
    catalog?: string,
    schema?: string
  ): Promise<PrimaryKeyMetadata[]> => {
    const params: Record<string, string> = { connectionId, tableName };
    if (catalog != null && catalog !== '') params.catalog = catalog;
    if (schema != null && schema !== '') params.schema = schema;

    const response = await http.get<PrimaryKeyMetadata[]>(ApiPaths.PRIMARY_KEYS, { params });
    return response.data;
  },
};
