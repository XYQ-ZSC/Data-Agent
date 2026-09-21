export enum SqlDialectEnum {
  MYSQL = 'mysql',
  POSTGRESQL = 'postgresql',
  PLSQL = 'plsql',
  N1QL = 'n1ql',
  DB2 = 'db2',
  REDSHIFT = 'redshift',
  SPARK = 'spark',
}

export type SqlDialect = 'mysql' | 'postgresql' | 'plsql' | 'n1ql' | 'db2' | 'redshift' | 'spark';

/**
 * Map a backend dbType code (e.g. "mysql", "dm") to the closest sql dialect
 * supported by sql-formatter. Dameng (DM) syntax is closest to Oracle/PLSQL.
 */
export function getSqlDialectByDbType(dbType?: string): SqlDialect {
  const normalized = (dbType ?? '').trim().toLowerCase();
  if (!normalized) return SqlDialectEnum.MYSQL;
  if (normalized.includes('postgres')) return SqlDialectEnum.POSTGRESQL;
  if (
    normalized === 'dm' ||
    normalized.includes('dameng') ||
    normalized.includes('oracle')
  ) {
    return SqlDialectEnum.PLSQL;
  }
  if (normalized.includes('db2')) return SqlDialectEnum.DB2;
  return SqlDialectEnum.MYSQL;
}
