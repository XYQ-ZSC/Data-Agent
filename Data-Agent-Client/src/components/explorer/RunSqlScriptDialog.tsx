import { useEffect, useRef, useState } from 'react';
import type { ChangeEvent } from 'react';
import { Check, FileCode2, Loader2, Play } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../ui/Dialog';
import { Button } from '../ui/Button';
import { cn } from '../../lib/utils';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { sqlExecutionService } from '../../services/sqlExecution.service';
import type { ExecuteSqlResponse, SqlBatchMode } from '../../types/sql';
import { containsNonAtomicStatement } from '../../utils/sqlRunner';
import { extractResultSet } from '../results/statementStatus';
import { StatementResultRow } from '../results/StatementResultRow';
import { useToast } from '../../hooks/useToast';

/** Where a right-clicked DB/SCHEMA/tables-folder node points a script run / DDL generation. */
export interface SqlScriptTarget {
  connectionId: number;
  connectionName: string;
  databaseName?: string | null;
  schemaName?: string | null;
  /** Human-readable "@host/db/schema" path shown in the dialog. */
  targetPath: string;
  /** Object scope for the DDL generator: whole schema vs. tables only. */
  scope?: 'all' | 'tables';
}

interface RunSqlScriptDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  target: SqlScriptTarget | null;
}

function readFileText(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '');
    reader.onerror = () => reject(reader.error ?? new Error('Failed to read file'));
    reader.readAsText(file);
  });
}

export function RunSqlScriptDialog({ open, onOpenChange, target }: RunSqlScriptDialogProps) {
  const { t } = useTranslation();
  const toast = useToast();
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const [fileName, setFileName] = useState('');
  const [fileContent, setFileContent] = useState('');
  const [mode, setMode] = useState<SqlBatchMode>('ATOMIC');
  const [timeoutSeconds, setTimeoutSeconds] = useState(30);
  const [maxRows, setMaxRows] = useState(10000);
  const [running, setRunning] = useState(false);
  const [done, setDone] = useState(false);
  const [results, setResults] = useState<ExecuteSqlResponse[] | null>(null);
  const [selectedStatement, setSelectedStatement] = useState(0);
  const doneTimerRef = useRef<number | null>(null);

  // Reset transient state each time the dialog is opened for a fresh run.
  useEffect(() => {
    if (!open) return;
    setFileName('');
    setFileContent('');
    setMode('ATOMIC');
    setResults(null);
    setSelectedStatement(0);
    setRunning(false);
    setDone(false);
    if (doneTimerRef.current !== null) {
      window.clearTimeout(doneTimerRef.current);
      doneTimerRef.current = null;
    }
  }, [open, target]);

  const handleFileChange = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    try {
      const content = await readFileText(file);
      setFileName(file.name);
      setFileContent(content);
      setResults(null);
      setSelectedStatement(0);
    } catch {
      toast.error(t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_NO_FILE));
    }
  };

  const handleRun = async () => {
    if (!target || !fileContent.trim() || running) return;

    // ATOMIC mode only accepts queries and transactional DML; DDL and other
    // non-atomic statements are rejected server-side. Auto-switch to STEPWISE.
    const effectiveMode: SqlBatchMode =
      mode === 'ATOMIC' && containsNonAtomicStatement(fileContent) ? 'STEPWISE' : mode;
    if (effectiveMode !== mode) setMode(effectiveMode);

    setRunning(true);
    setDone(false);
    setResults(null);
    try {
      const executed = await sqlExecutionService.executeScript({
        connectionId: target.connectionId,
        databaseName: target.databaseName ?? undefined,
        schemaName: target.schemaName ?? undefined,
        sql: fileContent,
        mode: effectiveMode,
        timeoutMs: timeoutSeconds * 1000,
        maxRows,
        executionId: crypto.randomUUID(),
      });
      setResults(executed);
      setSelectedStatement(0);
      const ok = executed.filter((result) => result.success).length;
      const failed = executed.length - ok;
      toast.success(t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_SUCCESS, { ok, failed }));
      setDone(true);
      if (doneTimerRef.current !== null) window.clearTimeout(doneTimerRef.current);
      doneTimerRef.current = window.setTimeout(() => {
        setDone(false);
        doneTimerRef.current = null;
      }, 2000);
    } catch (error: unknown) {
      toast.error(String(error));
    } finally {
      setRunning(false);
    }
  };

  const statementCount = results?.length ?? 0;
  const okCount = results?.filter((result) => result.success).length ?? 0;
  const failedCount = statementCount - okCount;
  const summary = statementCount > 0
    ? t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_SUMMARY, { total: statementCount, ok: okCount, failed: failedCount })
    : (results ? t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_NO_STATEMENTS) : null);

  const safeIndex = Math.min(selectedStatement, Math.max(0, statementCount - 1));
  const { headers: selectedHeaders, rows: selectedRows } = extractResultSet(results?.[safeIndex]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-base font-semibold">
            <FileCode2 className="h-4 w-4" />
            {t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_TITLE)}
          </DialogTitle>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          {/* Target (read-only, from the right-clicked node) */}
          <div className="flex flex-col gap-2">
            <label className="text-sm font-medium theme-text-primary">
              {t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_TARGET)}
            </label>
            <input
              type="text"
              readOnly
              value={target?.targetPath ?? ''}
              className="h-10 w-full rounded-md border border-gray-300 bg-gray-100 px-3 text-sm text-gray-500 focus:outline-none dark:border-gray-700 dark:bg-gray-800 dark:text-gray-400"
            />
          </div>

          {/* File picker */}
          <div className="flex flex-col gap-2">
            <label className="text-sm font-medium theme-text-primary">
              {t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_FILE)}
            </label>
            <div className="flex items-center gap-2">
              <input
                ref={fileInputRef}
                type="file"
                accept=".sql,text/plain,application/sql"
                className="hidden"
                onChange={(event) => { void handleFileChange(event); }}
              />
              <Button
                variant="outline"
                size="sm"
                onClick={() => fileInputRef.current?.click()}
                disabled={running}
              >
                {t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_SELECT_FILE)}
              </Button>
              <span
                className={cn(
                  'truncate text-sm',
                  fileName ? 'theme-text-primary' : 'theme-text-secondary opacity-60'
                )}
              >
                {fileName || t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_NO_FILE)}
              </span>
            </div>
          </div>

          {/* Execution options */}
          <div className="grid grid-cols-3 gap-4">
            <div className="flex flex-col gap-2">
              <label className="text-sm font-medium theme-text-primary">
                {t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_MODE)}
              </label>
              <select
                value={mode}
                onChange={(event) => setMode(event.target.value as SqlBatchMode)}
                disabled={running}
                className="h-10 w-full rounded-md border border-gray-300 bg-white px-3 text-sm text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/40 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
              >
                <option value="ATOMIC">{t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_MODE_ATOMIC)}</option>
                <option value="STEPWISE">{t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_MODE_STEPWISE)}</option>
              </select>
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-sm font-medium theme-text-primary">
                {t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_TIMEOUT)}
              </label>
              <input
                type="number"
                min={1}
                value={timeoutSeconds}
                onChange={(event) => setTimeoutSeconds(Math.max(1, Number(event.target.value)))}
                disabled={running}
                className="h-10 w-full rounded-md border border-gray-300 bg-white px-3 text-sm text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/40 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
              />
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-sm font-medium theme-text-primary">
                {t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_MAX_ROWS)}
              </label>
              <input
                type="number"
                min={1}
                value={maxRows}
                onChange={(event) => setMaxRows(Math.max(1, Number(event.target.value)))}
                disabled={running}
                className="h-10 w-full rounded-md border border-gray-300 bg-white px-3 text-sm text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/40 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
              />
            </div>
          </div>

          {/* Per-statement results */}
          {results && (
            <div className="flex flex-col gap-2">
              {summary && (
                <div className="text-xs theme-text-secondary">{summary}</div>
              )}
              <div className="max-h-44 space-y-1 overflow-y-auto rounded-md border border-gray-300 bg-white px-2 py-1.5 dark:border-gray-700 dark:bg-gray-900">
                {results.length === 0 ? (
                  <div className="px-1 py-2 text-center text-xs theme-text-secondary opacity-60">
                    {t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_NO_STATEMENTS)}
                  </div>
                ) : (
                  results.map((result, index) => (
                    <StatementResultRow
                      key={index}
                      result={result}
                      index={index}
                      selected={index === safeIndex}
                      onSelect={() => setSelectedStatement(index)}
                    />
                  ))
                )}
              </div>

              {/* Result set of the selected statement */}
              {selectedHeaders.length > 0 && (
                <div className="max-h-48 overflow-auto rounded-md border border-gray-300 bg-white dark:border-gray-700 dark:bg-gray-900">
                  <table className="w-full border-collapse text-[11px]">
                    <thead className="sticky top-0 bg-gray-100 dark:bg-gray-800">
                      <tr>
                        {selectedHeaders.map((header) => (
                          <th
                            key={header}
                            className="border-b border-r px-2 py-1 text-left font-medium theme-text-secondary last:border-r-0"
                          >
                            {header}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {selectedRows.length > 0 ? (
                        selectedRows.map((row, rowIndex) => (
                          <tr key={rowIndex} className="border-b border-gray-100 dark:border-gray-800">
                            {row.map((cell, cellIndex) => (
                              <td
                                key={cellIndex}
                                className="max-w-[240px] truncate border-r border-gray-100 px-2 py-1 theme-text-primary last:border-r-0 dark:border-gray-800"
                                title={cell == null ? 'NULL' : String(cell)}
                              >
                                {cell == null ? (
                                  <span className="italic text-gray-400">NULL</span>
                                ) : (
                                  String(cell)
                                )}
                              </td>
                            ))}
                          </tr>
                        ))
                      ) : (
                        <tr>
                          <td colSpan={selectedHeaders.length} className="px-2 py-3 text-center theme-text-secondary opacity-50">
                            {t(I18N_KEYS.EXPLORER.NO_DATA)}
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )}

          {/* Actions */}
          <div className="flex justify-end gap-2">
            <Button
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={running}
            >
              {t(I18N_KEYS.COMMON.CANCEL)}
            </Button>
            <Button onClick={() => { void handleRun(); }} disabled={!fileContent.trim() || running}>
              {running ? (
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              ) : done ? (
                <Check className="mr-2 h-4 w-4" />
              ) : (
                <Play className="mr-2 h-4 w-4" />
              )}
              {running
                ? t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_RUNNING)
                : done
                  ? t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_DONE)
                  : t(I18N_KEYS.EXPLORER.RUN_SQL_SCRIPT_RUN)}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
