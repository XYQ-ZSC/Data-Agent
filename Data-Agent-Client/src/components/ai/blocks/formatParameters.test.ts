import { describe, expect, it } from 'vitest';
import { formatParameters } from './formatParameters';

describe('formatParameters', () => {
  it('filters the UI-only top-level description parameter', () => {
    const formatted = formatParameters(JSON.stringify({
      connectionId: 8,
      databaseName: 'app',
      description: '检查 schema',
    }));

    expect(formatted).toBe(JSON.stringify({
      connectionId: 8,
      databaseName: 'app',
    }, null, 2));
  });

  it('does not filter nested business description fields', () => {
    const formatted = formatParameters(JSON.stringify({
      request: {
        description: '业务说明',
      },
      description: 'UI 展示说明',
    }));

    expect(formatted).toContain('"description": "业务说明"');
    expect(formatted).not.toContain('UI 展示说明');
  });
});
