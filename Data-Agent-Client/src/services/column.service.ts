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

  /**
   * Column names in table order (by ordinal position), filtering out any
   * column without a name.
   */
  listColumnNames: async (
    connectionId: string,
    tableName: string,
    catalog?: string,
    schema?: string
  ): Promise<string[]> => {
    const columns = await columnService.listColumns(connectionId, tableName, catalog, schema);
    return [...columns]
      .sort((a, b) => (a.ordinalPosition ?? 0) - (b.ordinalPosition ?? 0))
      .map((column) => column.name)
      .filter((name): name is string => Boolean(name));
  },
};
