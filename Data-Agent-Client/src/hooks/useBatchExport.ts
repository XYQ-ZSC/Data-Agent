import { useRef, useState, useCallback } from 'react';
import { tableDataService } from '../services/tableData.service';
import { downloadFile } from '../utils/download';
import { EXPORT_FILE_EXTENSIONS } from '../constants/fileTypes';
import type { ExportTableParams } from '../services/tableData.service';

export interface BatchExportProgress {
  current: number;
  total: number;
  table: string;
}

export interface BatchExportResult {
  table: string;
  success: boolean;
  message: string;
}

export function useBatchExport() {
  const cancelRef = useRef(false);
  const [exporting, setExporting] = useState(false);
  const [progress, setProgress] = useState<BatchExportProgress | null>(null);
  const [results, setResults] = useState<BatchExportResult[]>([]);

  const start = useCallback(
    async (
      tables: string[],
      format: string,
      baseParams: Omit<ExportTableParams, 'tableName' | 'columns'>,
      columnSelections: Record<string, string[]>
    ) => {
      cancelRef.current = false;
      setExporting(true);
      setResults([]);
      const collected: BatchExportResult[] = [];
      let success = 0;

      for (let i = 0; i < tables.length; i++) {
        if (cancelRef.current) {
          break;
        }
        const table = tables[i];
        setProgress({ current: i + 1, total: tables.length, table });

        try {
          const params: ExportTableParams = {
            ...baseParams,
            tableName: table,
            columns: columnSelections[table] || [],
          };
          const blob = await tableDataService.exportTable(params);
          downloadFile(blob, `${table}.${EXPORT_FILE_EXTENSIONS[format] ?? 'csv'}`);
          collected.push({ table, success: true, message: '' });
          success += 1;
        } catch (error: unknown) {
          const msg = error instanceof Error ? error.message : String(error);
          collected.push({ table, success: false, message: msg });
        }
        setResults([...collected]);
      }

      setExporting(false);
      setProgress(null);
      return { success, failed: collected.length - success, results: collected, cancelled: cancelRef.current };
    },
    []
  );

  const stop = useCallback(() => {
    cancelRef.current = true;
  }, []);

  const reset = useCallback(() => {
    cancelRef.current = true;
    setExporting(false);
    setProgress(null);
    setResults([]);
  }, []);

  return { exporting, progress, results, start, stop, reset };
}
