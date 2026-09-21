import http from '../lib/http';
import { ApiPaths } from '../constants/apiPaths';

export interface ColumnMetadata {
  name: string;
  dataType: number;
  typeName: string;
  columnSize: number;
  decimalDigits: number;
  nullable: boolean;
  ordinalPosition: number;
  remarks: string;
  isPrimaryKeyPart?: boolean;
  isAutoIncrement?: boolean;
  isUnsigned?: boolean;
  defaultValue?: string | null;
}

export const columnService = {
  listColumns: async (
    connectionId: string,
    tableName: string,
    catalog?: string,
    schema?: string
  ): Promise<ColumnMetadata[]> => {
    const params: Record<string, string> = { connectionId, tableName };
    if (catalog != null && catalog !== '') params.catalog = catalog;
    if (schema != null && schema !== '') params.schema = schema;

    const response = await http.get<ColumnMetadata[]>(ApiPaths.COLUMNS, { params });
    return response.data;
  },
};
