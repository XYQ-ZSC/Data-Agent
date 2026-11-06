import type { TFunction } from 'i18next';
import type { ImportTableDataResult } from '../services/tableData.service';
import { I18N_KEYS } from '../constants/i18nKeys';

/**
 * Formats an import result as a localized message. For a failed import, an
 * SQL file reports the failing statement while row-based formats report the
 * failing row.
 */
export function formatImportResult(
  t: TFunction,
  fileType: string,
  result: ImportTableDataResult
): string {
  if (result.success) {
    return t(I18N_KEYS.EXPLORER.IMPORT_SUCCESS, { count: result.insertedRows });
  }
  if (result.failedAtRow) {
    return fileType === 'SQL'
      ? t(I18N_KEYS.EXPLORER.IMPORT_FAILED_STATEMENT, {
          row: result.failedAtRow,
          message: result.errorMessage ?? '',
        })
      : t(I18N_KEYS.EXPLORER.IMPORT_FAILED_ROW, {
          row: result.failedAtRow,
          message: result.errorMessage ?? '',
        });
  }
  return result.errorMessage ?? t(I18N_KEYS.EXPLORER.IMPORT_FAILED);
}
