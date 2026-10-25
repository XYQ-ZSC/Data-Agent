import { beforeEach, describe, expect, it, vi } from 'vitest';
import http from '../lib/http';
import { aiService } from './ai.service';
import { FALLBACK_MODELS, defaultModelSelection, modelRequest } from '../constants/models';

vi.mock('../lib/http', () => ({ default: { get: vi.fn() } }));

describe('workspace model availability', () => {
  beforeEach(() => vi.resetAllMocks());

  it('preserves distinct configuration IDs for identical upstream names', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: [
      { modelName: 'same-model', modelConfigId: 'config-a', name: 'Primary', defaultModel: true },
      { modelName: 'same-model', modelConfigId: 'config-b', name: 'Backup' },
    ] });
    const options = await aiService.getModels();
    expect(options.map((m) => m.modelName)).toEqual(['config-a', 'config-b']);
    expect(modelRequest('config-b', options)).toEqual({ modelConfigId: 'config-b' });
    expect(options[0].defaultModel).toBe(true);
    expect(defaultModelSelection([...options].reverse())).toBe('config-a');
    expect(defaultModelSelection([])).toBe('');
  });

  it('does not reuse a previous workspace list or supply callable hardcoded models on failure', async () => {
    vi.mocked(http.get).mockResolvedValueOnce({ data: [{ modelName: 'old-model' }] }).mockRejectedValueOnce(new Error('unavailable'));
    expect(await aiService.getModels()).toHaveLength(1);
    expect(await aiService.getModels()).toEqual([]);
    expect(FALLBACK_MODELS).toEqual([]);
    expect(modelRequest('old-model', [])).toEqual({ model: '__unavailable__' });
  });

  it('retains legacy server compatibility without inventing model capabilities', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: [{ modelName: 'legacy', memoryThreshold: -1 }] });
    const options = await aiService.getModels();
    expect(modelRequest('legacy', options)).toEqual({ model: 'legacy' });
    expect(options[0].supportThinking).toBe(false);
    expect(options[0].memoryThreshold).toBeNull();
  });
});
