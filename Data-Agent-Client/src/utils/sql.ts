import { format } from 'sql-formatter';
import { SqlDialectEnum, type SqlDialect } from '../constants/sqlDialect';

/**
 * Format SQL string using sql-formatter
 * @param sql - The SQL string to format
 * @param dialect - The SQL dialect (default: mysql)
 * @returns Formatted SQL string, or original if formatting fails
 */
export function formatSql(
  sql: string,
  dialect: SqlDialect = SqlDialectEnum.MYSQL,
  dbType?: string
): string {
  if (!sql || !sql.trim()) return sql;
  if (dbType?.trim().toLowerCase() === 'dm') {
    return formatDmSql(sql);
  }
  try {
    return format(sql, { language: dialect });
  } catch {
    return sql;
  }
}

/** Keep DM statements unchanged unless formatting preserves every SQL token. */
export function formatDmSql(sql: string): string {
  if (!sql.trim()) return sql;
  // The PLSQL formatter is not a DM block parser. These blocks may contain
  // internal semicolons, slash terminators and executable comments.
  if (/\b(?:DECLARE|BEGIN)\b|\bCREATE\s+(?:OR\s+REPLACE\s+)?(?:PROCEDURE|FUNCTION|TRIGGER|PACKAGE|TYPE)\b/i.test(sql)
      || /--|\/\*/.test(sql)
      || /(?:^|\r?\n)[ \t]*\/[ \t]*(?:\r?\n|$)/.test(sql)) {
    return sql;
  }
  const before = dmTokens(sql);
  if (!before) return sql;
  try {
    const formatted = format(sql, { language: SqlDialectEnum.PLSQL });
    if (/--|\/\*/.test(formatted)) return sql;
    const after = dmTokens(formatted);
    if (!after || before.length !== after.length || before.some((token, index) => token !== after[index])) {
      return sql;
    }
    return format(formatted, { language: SqlDialectEnum.PLSQL }) === formatted ? formatted : sql;
  } catch {
    return sql;
  }
}

function dmTokens(sql: string): string[] | null {
  const tokens: string[] = [];
  for (let i = 0; i < sql.length;) {
    if (/\s/.test(sql[i])) { i++; continue; }
    const start = i;
    if (sql[i] === "'" || sql[i] === '"') {
      const quote = sql[i++];
      let closed = false;
      while (i < sql.length) {
        if (sql[i] === quote) {
          if (sql[i + 1] === quote) { i += 2; continue; }
          i++;
          closed = true;
          break;
        }
        i++;
      }
      if (!closed) return null;
    } else if (/[\p{L}\p{N}_$#]/u.test(sql[i])) {
      while (i < sql.length && /[\p{L}\p{N}_$#]/u.test(sql[i])) i++;
    } else {
      i++;
    }
    tokens.push(sql.slice(start, i));
  }
  return tokens;
}
