import http from '../lib/http';
import { ApiPaths } from '../constants/apiPaths';

export interface SequenceMetadata {
  name: string;
  minValue: number | string | null;
  maxValue: number | string | null;
  incrementBy: number | string | null;
  cycle: boolean;
  cacheSize: number | null;
}

export const sequenceService = {
  async listSequences(connectionId: string, catalog?: string, schema?: string): Promise<SequenceMetadata[]> {
    const response = await http.get<SequenceMetadata[]>(ApiPaths.SEQUENCES, {
      params: { connectionId, catalog: catalog || undefined, schema: schema || undefined },
    });
    return response.data;
  },

  async getSequenceDdl(connectionId: string, sequenceName: string, catalog?: string, schema?: string): Promise<string> {
    const response = await http.get<string>(ApiPaths.SEQUENCES_DDL, {
      params: { connectionId, sequenceName, catalog: catalog || undefined, schema: schema || undefined },
    });
    return response.data;
  },

  async deleteSequence(connectionId: string, sequenceName: string, catalog?: string, schema?: string): Promise<void> {
    await http.delete(ApiPaths.SEQUENCES, {
      data: { connectionId, sequenceName, catalog, schema },
    });
  },
};
