import { describe, expect, it } from 'vitest';
import { sanitizeToolDescription } from './BackgroundToolGroupBlock';

describe('BackgroundToolGroupBlock', () => {
  it('uses description text without exposing the raw tool name', () => {
    expect(sanitizeToolDescription('检查订单表结构', 'getObjectDetail')).toBe('检查订单表结构');
  });

  it('falls back to display labels when description is missing', () => {
    expect(sanitizeToolDescription(undefined, 'Get Databases')).toBe('Get Databases');
  });

  it('normalizes whitespace and limits long descriptions', () => {
    const value = sanitizeToolDescription(`  ${'a'.repeat(120)}\n b  `, 'Fallback');

    expect(value).toHaveLength(80);
    expect(value.endsWith('...')).toBe(true);
    expect(value).not.toContain('\n');
  });
});
