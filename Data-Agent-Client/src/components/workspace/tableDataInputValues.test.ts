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

  it('keeps DM numeric edits as text until JDBC can bind them without JavaScript precision loss', () => {
    expect(normalizeEditedCellInput('9007199254740993', 1, true)).toBe('9007199254740993');
    expect(normalizeEditedCellInput('1234567890.123456789', 1, true)).toBe('1234567890.123456789');
  });
});
