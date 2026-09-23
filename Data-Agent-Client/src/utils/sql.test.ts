import { describe, expect, it } from 'vitest';
import { formatDmSql, formatSql } from './sql';

describe('DM SQL formatting', () => {
  it('formats a plain query without changing a quoted identifier or literal', () => {
    const sql = `select "Mixed;Case", 'a;b' from "CaseSchema"."CaseTable" where id=1;`;
    const formatted = formatSql(sql, 'plsql', 'dm');
    expect(formatted).not.toBe(sql);
    expect(formatted).toContain('"Mixed;Case"');
    expect(formatted).toContain('"CaseSchema"."CaseTable"');
    expect(formatted).toContain("'a;b'");
    expect(formatDmSql(formatted)).toBe(formatted);
  });

  it('keeps procedural blocks and scripts containing them unchanged', () => {
    const sql = 'CREATE OR REPLACE PROCEDURE P AS BEGIN NULL; END;\n/\nSELECT 1;';
    expect(formatDmSql(sql)).toBe(sql);
    expect(formatDmSql('BEGIN NULL; END;')).toBe('BEGIN NULL; END;');
  });

  it('keeps statements with comments and unterminated literals unchanged', () => {
    expect(formatDmSql('SELECT 1; -- keep this\nSELECT 2;')).toBe('SELECT 1; -- keep this\nSELECT 2;');
    expect(formatDmSql("SELECT 'oops")).toBe("SELECT 'oops");
  });

  it('preserves a DM TOP clause and remains idempotent', () => {
    const formatted = formatDmSql('select top 2 "Value" from "CaseTable";');
    expect(formatted).toContain('top 2');
    expect(formatted).toContain('"Value"');
    expect(formatDmSql(formatted)).toBe(formatted);
  });
});
