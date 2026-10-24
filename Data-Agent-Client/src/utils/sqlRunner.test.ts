import { describe, expect, it } from 'vitest';
import {
  containsNonAtomicStatement,
  splitSqlSpans,
  splitSqlStatements,
  statementSpanAtOffset,
} from './sqlRunner';

describe('splitSqlStatements', () => {
  it('splits on top-level semicolons only', () => {
    expect(splitSqlStatements('SELECT 1; SELECT 2;')).toEqual(['SELECT 1', 'SELECT 2']);
    expect(splitSqlStatements("SELECT 'a;b'; SELECT 2;")).toEqual(["SELECT 'a;b'", 'SELECT 2']);
    expect(splitSqlStatements('SELECT 1 /* ; */; SELECT 2;')).toEqual(['SELECT 1 /* ; */', 'SELECT 2']);
    expect(splitSqlStatements('-- c;\nSELECT 1;')).toEqual(['-- c;\nSELECT 1']);
    expect(splitSqlStatements('')).toEqual([]);
  });
});

describe('splitSqlSpans', () => {
  it('tracks trimmed offsets and raw segment ranges', () => {
    const [a, b] = splitSqlSpans('SELECT 1; SELECT 2;');
    expect(a).toEqual({ sql: 'SELECT 1', start: 0, end: 8, segmentStart: 0, segmentEnd: 8 });
    expect(b).toEqual({ sql: 'SELECT 2', start: 10, end: 18, segmentStart: 9, segmentEnd: 18 });
  });

  it('skips empty segments', () => {
    expect(splitSqlSpans('SELECT 1;;')).toHaveLength(1);
    expect(splitSqlSpans('   ')).toHaveLength(0);
  });
});

describe('statementSpanAtOffset', () => {
  it('locates the statement containing the cursor', () => {
    const sql = 'SELECT 1; SELECT 2;';
    expect(statementSpanAtOffset(sql, 0)?.sql).toBe('SELECT 1');
    expect(statementSpanAtOffset(sql, 8)?.sql).toBe('SELECT 1'); // on the `;`
    expect(statementSpanAtOffset(sql, 12)?.sql).toBe('SELECT 2');
    expect(statementSpanAtOffset('SELECT 1; SELECT 2', 17)?.sql).toBe('SELECT 2'); // EOF, no trailing `;`
  });

  it('returns null past the final statement', () => {
    expect(statementSpanAtOffset('SELECT 1;', 9)).toBeNull(); // after the trailing `;`
    expect(statementSpanAtOffset('SELECT 1;  ', 11)).toBeNull(); // trailing whitespace
  });
});

describe('containsNonAtomicStatement', () => {
  it('returns false for queries and transactional DML', () => {
    expect(containsNonAtomicStatement('SELECT * FROM t;')).toBe(false);
    expect(containsNonAtomicStatement('WITH cte AS (SELECT 1) SELECT * FROM cte;')).toBe(false);
    expect(containsNonAtomicStatement('INSERT INTO t VALUES (1); DELETE FROM t WHERE id=1;')).toBe(false);
    expect(containsNonAtomicStatement('UPDATE t SET x=1 WHERE id=1;')).toBe(false);
    expect(containsNonAtomicStatement('')).toBe(false);
  });

  it('returns true for DDL and other non-atomic statements', () => {
    expect(containsNonAtomicStatement('DROP TABLE demo;')).toBe(true);
    expect(containsNonAtomicStatement('CREATE TABLE t (id INT);')).toBe(true);
    expect(containsNonAtomicStatement('ALTER TABLE t ADD c INT;')).toBe(true);
    expect(containsNonAtomicStatement('TRUNCATE TABLE t;')).toBe(true);
    expect(containsNonAtomicStatement('SHOW TABLES;')).toBe(true);
  });

  it('returns true when any statement in a mixed script is non-atomic', () => {
    expect(containsNonAtomicStatement('SELECT 1; DROP TABLE demo;')).toBe(true);
  });
});
