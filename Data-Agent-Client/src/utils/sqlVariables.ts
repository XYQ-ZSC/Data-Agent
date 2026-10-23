/**
 * Parameter placeholders inside a SQL script: `${name}`.
 *
 * The scanner shells out comments (`--`, `#` for MySQL, `/* *\/`) and tracks
 * string/identifier quoting so that placeholders inside comments are never
 * collected, while placeholders inside string literals get their value
 * escaped when substituted.
 */

export interface SqlVariable {
  name: string;
  occurrences: number;
}

interface Occurrence {
  name: string;
  start: number;
  end: number;
  stringLiteral: boolean;
}

const PLACEHOLDER = /^\$\{([A-Za-z_][A-Za-z0-9_]*)\}/;

const isSpace = (value: string | undefined) => value !== undefined && /\s/.test(value);

function lineEnd(sql: string, index: number): number {
  let i = index;
  while (i < sql.length && sql[i] !== '\n' && sql[i] !== '\r') i++;
  return i;
}

/**
 * Walk a quoted run ('...', "..." or `...`) and collect placeholders inside it.
 * Returns the offset right after the closing quote (or the end of the input).
 */
function scanQuoted(
  sql: string,
  start: number,
  quote: string,
  mysql: boolean,
  stringLiteral: boolean,
  occurrences: Occurrence[],
): number {
  let i = start + 1;
  const backslashEscapes = mysql && (quote === "'" || quote === '"');
  while (i < sql.length) {
    const char = sql[i];
    if (backslashEscapes && char === '\\') {
      i += 2;
      continue;
    }
    if (char === quote) {
      // A doubled quote is an escaped quote, not the end of the run.
      if (sql[i + 1] === quote) {
        i += 2;
        continue;
      }
      return i + 1;
    }
    if (char === '$') {
      const match = PLACEHOLDER.exec(sql.slice(i));
      if (match) {
        occurrences.push({
          name: match[1],
          start: i,
          end: i + match[0].length,
          stringLiteral,
        });
        i += match[0].length;
        continue;
      }
    }
    i++;
  }
  return i;
}

function scan(sql: string, mysql: boolean): Occurrence[] {
  const occurrences: Occurrence[] = [];
  let i = 0;
  while (i < sql.length) {
    const char = sql[i];
    if (char === '-' && sql.startsWith('--', i) && (i + 2 >= sql.length || isSpace(sql[i + 2]))) {
      i = lineEnd(sql, i + 2);
      continue;
    }
    if (mysql && char === '#') {
      i = lineEnd(sql, i + 1);
      continue;
    }
    if (char === '/' && sql[i + 1] === '*') {
      const end = sql.indexOf('*/', i + 2);
      i = end < 0 ? sql.length : end + 2;
      continue;
    }
    if (char === "'" || char === '"' || char === '`') {
      // MySQL treats double quotes as string literals; DM treats them as identifiers.
      const stringLiteral = char === "'" || (char === '"' && mysql);
      i = scanQuoted(sql, i, char, mysql, stringLiteral, occurrences);
      continue;
    }
    if (char === '$') {
      const match = PLACEHOLDER.exec(sql.slice(i));
      if (match) {
        occurrences.push({ name: match[1], start: i, end: i + match[0].length, stringLiteral: false });
        i += match[0].length;
        continue;
      }
    }
    i++;
  }
  return occurrences;
}

function escapeStringLiteral(value: string, mysql: boolean): string {
  const quoted = value.replace(/'/g, "''");
  // MySQL also treats a backslash as an escape character inside string literals.
  return mysql ? quoted.replace(/\\/g, '\\\\') : quoted;
}

/** Unique parameter names in first-appearance order, with their total occurrence count. */
export function extractSqlVariables(sql: string, mysql: boolean): SqlVariable[] {
  const counts = new Map<string, number>();
  for (const occurrence of scan(sql ?? '', mysql)) {
    counts.set(occurrence.name, (counts.get(occurrence.name) ?? 0) + 1);
  }
  return Array.from(counts, ([name, occurrences]) => ({ name, occurrences }));
}

/**
 * Replace every known `${name}` placeholder, leaving comments and unknown
 * placeholders untouched. Values inside string literals are escaped.
 */
export function substituteSqlVariables(sql: string, values: Record<string, string>, mysql: boolean): string {
  const occurrences = scan(sql ?? '', mysql).filter(occurrence =>
    Object.prototype.hasOwnProperty.call(values, occurrence.name));
  if (occurrences.length === 0) return sql ?? '';
  let output = '';
  let cursor = 0;
  for (const occurrence of occurrences) {
    output += (sql ?? '').slice(cursor, occurrence.start);
    const value = values[occurrence.name] ?? '';
    output += occurrence.stringLiteral ? escapeStringLiteral(value, mysql) : value;
    cursor = occurrence.end;
  }
  return output + (sql ?? '').slice(cursor);
}
