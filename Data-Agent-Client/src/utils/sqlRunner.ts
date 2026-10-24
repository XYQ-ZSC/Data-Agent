/**
 * SQL script runner helpers.
 *
 * The SQL console executes a whole editor script through the backend's batch
 * API. This module holds the pure client-side logic for preparing a script for
 * execution — splitting it into statements and deciding which batch mode the
 * server will accept.
 *
 * Note: this is deliberately kept OUT of `utils/sql.ts` (SQL formatting) and
 * `utils/sqlGenerator/` (SQL generation). Formatting transforms text; the
 * generator turns data into SQL text; the runner takes SQL text and executes it.
 */

/** Statements the server accepts in ATOMIC batch mode (transactional DML / queries). */
const ATOMIC_FIRST_KEYWORDS = new Set(['SELECT', 'WITH', 'INSERT', 'UPDATE', 'DELETE', 'MERGE']);

/** One top-level statement plus its offsets in the original script. */
export interface SqlStatementSpan {
  /** Executable statement text, trimmed of surrounding whitespace. */
  sql: string;
  /** Offset of the first non-whitespace character (inclusive). */
  start: number;
  /** Offset just past the last non-whitespace character (exclusive). */
  end: number;
  /** Offset of the raw segment start (inclusive; may include leading whitespace/comments). */
  segmentStart: number;
  /** Offset of the raw segment end (the terminating `;`, or `sql.length` for the last). */
  segmentEnd: number;
}

/**
 * Split a SQL script into top-level statements on `;`, respecting string literals,
 * quoted identifiers, and `--` / `/* *​/` comments. Returns trimmed statement text
 * together with its offsets in the original string.
 */
export function splitSqlSpans(sql: string): SqlStatementSpan[] {
  const segments: Array<{ start: number; end: number }> = [];
  let segmentStart = 0;
  let singleQuote = false;
  let doubleQuote = false;
  let lineComment = false;
  let blockComment = false;

  for (let i = 0; i < sql.length; i++) {
    const c = sql[i];
    const next = sql[i + 1];

    if (lineComment) {
      if (c === '\n') lineComment = false;
      continue;
    }
    if (blockComment) {
      if (c === '*' && next === '/') {
        i++;
        blockComment = false;
      }
      continue;
    }
    if (singleQuote) {
      if (c === "'" && next === "'") i++;
      else if (c === "'") singleQuote = false;
      continue;
    }
    if (doubleQuote) {
      if (c === '"' && next === '"') i++;
      else if (c === '"') doubleQuote = false;
      continue;
    }
    if (c === '-' && next === '-') {
      i++;
      lineComment = true;
      continue;
    }
    if (c === '/' && next === '*') {
      i++;
      blockComment = true;
      continue;
    }
    if (c === "'") {
      singleQuote = true;
      continue;
    }
    if (c === '"') {
      doubleQuote = true;
      continue;
    }
    if (c === ';') {
      segments.push({ start: segmentStart, end: i });
      segmentStart = i + 1;
    }
  }
  segments.push({ start: segmentStart, end: sql.length });

  const spans: SqlStatementSpan[] = [];
  for (const { start: segmentStart, end: segmentEnd } of segments) {
    const raw = sql.slice(segmentStart, segmentEnd);
    const trimmed = raw.trim();
    if (!trimmed) continue;
    spans.push({
      sql: trimmed,
      start: segmentStart + (raw.length - raw.trimStart().length),
      end: segmentStart + raw.trimEnd().length,
      segmentStart,
      segmentEnd,
    });
  }
  return spans;
}

/** Split a SQL script into top-level statements, returning trimmed statement text only. */
export function splitSqlStatements(sql: string): string[] {
  return splitSqlSpans(sql).map((span) => span.sql);
}

/**
 * Locate the statement whose raw segment contains the given offset (a cursor).
 * A cursor sitting on the terminating `;` still maps to the preceding statement.
 * Returns null when the offset falls after the last statement (trailing whitespace).
 */
export function statementSpanAtOffset(sql: string, offset: number): SqlStatementSpan | null {
  return splitSqlSpans(sql).find((span) => offset >= span.segmentStart && offset <= span.segmentEnd) ?? null;
}

/** Return the leading keyword of a statement, or '' when it starts with a comment. */
function firstKeyword(statement: string): string {
  let s = statement.trimStart();
  while (s.startsWith('--') || s.startsWith('/*')) {
    if (s.startsWith('--')) {
      const nl = s.indexOf('\n');
      s = nl === -1 ? '' : s.slice(nl + 1).trimStart();
    } else {
      const end = s.indexOf('*/');
      s = end === -1 ? '' : s.slice(end + 2).trimStart();
    }
  }
  const match = s.match(/^[\p{L}][\p{L}\p{N}_$#]*/u);
  return match ? match[0].toUpperCase() : '';
}

/**
 * True when the script contains a statement the server's ATOMIC batch mode rejects
 * (anything that is not a query or transactional DML — e.g. DROP/CREATE/ALTER/TRUNCATE).
 * Such scripts must run in STEPWISE mode.
 */
export function containsNonAtomicStatement(sql: string): boolean {
  return splitSqlStatements(sql).some((statement) => {
    const keyword = firstKeyword(statement);
    return keyword !== '' && !ATOMIC_FIRST_KEYWORDS.has(keyword);
  });
}
