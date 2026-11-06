/** File formats offered by the table export dialog. */
export const EXPORT_FILE_TYPES = ['CSV', 'XLSX', 'XLS', 'JSON', 'SQL'] as const;

/** Maps an export file type to its download file extension. */
export const EXPORT_FILE_EXTENSIONS: Record<string, string> = {
  CSV: 'csv',
  XLSX: 'xlsx',
  XLS: 'xls',
  JSON: 'json',
  SQL: 'sql',
};

/** Maps a lowercase file extension to the backend file-type token. */
export const IMPORT_FILE_TYPES: Record<string, string> = {
  csv: 'CSV',
  json: 'JSON',
  sql: 'SQL',
  xlsx: 'XLSX',
  xls: 'XLS',
};
