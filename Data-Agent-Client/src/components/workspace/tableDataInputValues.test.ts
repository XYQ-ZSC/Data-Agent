import { describe, expect, it } from 'vitest';
import { normalizeEditedCellInput, normalizeInsertInput } from './tableDataInputValues';

describe('DM table input values', () => {
  it('distinguishes untouched, empty, and explicit NULL on insert', () => {
    expect(normalizeInsertInput(undefined, true)).toBeNull();
    expect(normalizeInsertInput('', true)).toBe('');
    expect(normalizeInsertInput('NULL', true)).toBeNull();
    expect(normalizeInsertInput('  ', true)).toBe('  ');
  });

  it('distinguishes cleared cells from SQL NULL without changing other databases', () => {
    expect(normalizeEditedCellInput('', null, true)).toBe('');
    expect(normalizeEditedCellInput('NULL', '', true)).toBeNull();
    expect(normalizeEditedCellInput('', 'old', false)).toBeNull();
    expect(normalizeInsertInput('', false)).toBeNull();
  });
});
