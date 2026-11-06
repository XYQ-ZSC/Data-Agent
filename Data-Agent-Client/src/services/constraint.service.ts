import http from '../lib/http';
import { ApiPaths } from '../constants/apiPaths';

export interface ConstraintMetadata {
  name: string;
  tableName: string;
  type: string;
  columns: string[];
  referencedSchema: string | null;
  referencedTable: string | null;
  referencedColumns: string[];
  checkExpression: string | null;
  enabled: boolean;
}

export const constraintService = {
  async listConstraints(connectionId: string, tableName: string, catalog?: string, schema?: string): Promise<ConstraintMetadata[]> {
    const response = await http.get<ConstraintMetadata[]>(ApiPaths.CONSTRAINTS, {
      params: { connectionId, tableName, catalog: catalog || undefined, schema: schema || undefined },
    });
    return response.data;
  },

  async getConstraintDdl(connectionId: string, constraintName: string, catalog?: string, schema?: string): Promise<string> {
    const response = await http.get<string>(ApiPaths.CONSTRAINTS_DDL, {
      params: { connectionId, constraintName, catalog: catalog || undefined, schema: schema || undefined },
    });
    return response.data;
  },

  async deleteConstraint(connectionId: string, tableName: string, constraintName: string, catalog?: string, schema?: string): Promise<void> {
    await http.delete(ApiPaths.CONSTRAINTS, {
      data: { connectionId, tableName, constraintName, catalog, schema },
    });
  },
};
