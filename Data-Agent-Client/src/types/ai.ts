/** Option for chat model selection (matches backend ModelOptionResponse). */
export interface ModelOption {
  modelName: string;
  modelConfigId?: string;
  name?: string;
  provider?: string;
  apiStyle?: string;
  defaultModel?: boolean;
  supportThinking: boolean;
  memoryThreshold: number | null;
  maxContextTokens: number | null;
}
