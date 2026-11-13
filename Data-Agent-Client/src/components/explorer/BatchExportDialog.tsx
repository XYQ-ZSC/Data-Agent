import { useEffect, useState } from 'react';
import { Download, Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '../ui/Dialog';
import { Button } from '../ui/Button';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { EXPORT_FILE_TYPES, EXPORT_FILE_EXTENSIONS } from '../../constants/fileTypes';
import { tableService } from '../../services/table.service';
import { columnService } from '../../services/column.service';
import { useToast } from '../../hooks/useToast';
import { useBatchExport } from '../../hooks/useBatchExport';

export interface BatchExportParams {
  connectionId: number;
  catalog?: string;
  schema?: string;
}

interface BatchExportDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  targetPath: string;
  exportParams: BatchExportParams | null;
}

export function BatchExportDialog({
  open,
  onOpenChange,
  targetPath,
  exportParams,
}: BatchExportDialogProps) {
  const { t } = useTranslation();
  const toast = useToast();
  const [step, setStep] = useState<1 | 2>(1);
  const [fileType, setFileType] = useState<string>('CSV');
  const [tables, setTables] = useState<string[]>([]);
  const [tablesLoading, setTablesLoading] = useState(false);
  const [tablesLoadFailed, setTablesLoadFailed] = useState(false);
  const [search, setSearch] = useState('');
  const [selectedTables, setSelectedTables] = useState<Set<string>>(new Set());
  const [fieldCache, setFieldCache] = useState<Record<string, string[]>>({});
  const [fieldSelections, setFieldSelections] = useState<Record<string, Set<string>>>({});
  const [activeSourceTable, setActiveSourceTable] = useState('');
  const [fieldsLoading, setFieldsLoading] = useState<Set<string>>(new Set());

  const batchExport = useBatchExport();

  const fileExtension = EXPORT_FILE_EXTENSIONS[fileType] ?? 'csv';

  const filteredTables = search
    ? tables.filter((name) => name.toLowerCase().includes(search.toLowerCase()))
    : tables;
  const queuedTables = tables.filter((name) => selectedTables.has(name));
  const selectedCount = queuedTables.length;
  const activeFieldsLoading = fieldsLoading.has(activeSourceTable);
  const activeFields = fieldCache[activeSourceTable] ?? [];
  const activeSelection = fieldSelections[activeSourceTable];
  const activeSelectedCount = activeSelection
    ? activeFields.filter((name) => activeSelection.has(name)).length
    : activeFields.length;
  // A selected table whose field list was loaded and then fully deselected blocks the export.
  const hasEmptySelection = queuedTables.some(
    (table) => (fieldCache[table]?.length ?? 0) > 0 && (fieldSelections[table]?.size ?? 0) === 0
  );

  useEffect(() => {
    if (!open || !exportParams) {
      return;
    }
    let cancelled = false;
    setStep(1);
    setTablesLoading(true);
    setTablesLoadFailed(false);
    setSearch('');
    batchExport.reset();
    setFieldCache({});
    setFieldSelections({});
    setFieldsLoading(new Set());
    setActiveSourceTable('');
    tableService
      .listTables(String(exportParams.connectionId), exportParams.catalog, exportParams.schema)
      .then((names) => {
        if (cancelled) return;
        const sorted = [...names].filter(Boolean).sort((a, b) => a.localeCompare(b));
        setTables(sorted);
        setSelectedTables(new Set(sorted));
        setTablesLoading(false);
      })
      .catch(() => {
        if (cancelled) return;
        setTables([]);
        setSelectedTables(new Set());
        setTablesLoadFailed(true);
        setTablesLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, exportParams, batchExport.reset]);

  const loadFieldsIfNeeded = (table: string) => {
    if (!exportParams || !table || fieldCache[table] !== undefined) {
      return;
    }
    setFieldsLoading((previous) => new Set(previous).add(table));
    columnService
      .listColumnNames(String(exportParams.connectionId), table, exportParams.catalog, exportParams.schema)
      .then((names) => {
        setFieldCache((previous) => ({ ...previous, [table]: names }));
        setFieldSelections((previous) =>
          previous[table] ? previous : { ...previous, [table]: new Set(names) }
        );
        setFieldsLoading((previous) => {
          const next = new Set(previous);
          next.delete(table);
          return next;
        });
      })
      .catch(() => {
        // Field list unavailable: an empty cache entry means "export all columns" below.
        setFieldCache((previous) => ({ ...previous, [table]: [] }));
        setFieldsLoading((previous) => {
          const next = new Set(previous);
          next.delete(table);
          return next;
        });
      });
  };

  const handleToggleTable = (name: string) => {
    setSelectedTables((previous) => {
      const next = new Set(previous);
      if (next.has(name)) {
        next.delete(name);
      } else {
        next.add(name);
      }
      return next;
    });
  };

  const handleGoToStep2 = () => {
    const first = queuedTables[0] ?? '';
    setActiveSourceTable(first);
    loadFieldsIfNeeded(first);
    setStep(2);
  };

  const handleSourceTableChange = (table: string) => {
    setActiveSourceTable(table);
    loadFieldsIfNeeded(table);
  };

  const handleToggleField = (name: string) => {
    if (!activeSourceTable) {
      return;
    }
    setFieldSelections((previous) => {
      const current = previous[activeSourceTable] ?? new Set(activeFields);
      const next = new Set(current);
      if (next.has(name)) {
        next.delete(name);
      } else {
        next.add(name);
      }
      return { ...previous, [activeSourceTable]: next };
    });
  };

  const handleOpenChange = (next: boolean) => {
    // Closing the dialog while exporting stops the remaining tables.
    if (!next && batchExport.exporting) {
      batchExport.stop();
    }
    onOpenChange(next);
  };

  const handleBatchExport = async () => {
    if (!exportParams || batchExport.exporting || selectedCount === 0) {
      return;
    }
    const baseParams = {
      connectionId: exportParams.connectionId,
      catalog: exportParams.catalog,
      schema: exportParams.schema,
      fileType,
    };
    const columnSelections: Record<string, string[]> = {};
    for (const table of queuedTables) {
      const cache = fieldCache[table];
      const selection = fieldSelections[table];
      columnSelections[table] =
        cache && cache.length > 0 && selection ? cache.filter((name) => selection.has(name)) : [];
    }
    const outcome = await batchExport.start(queuedTables, fileType, baseParams, columnSelections);
    if (!outcome.cancelled) {
      const summary = t(I18N_KEYS.EXPLORER.EXPORT_BATCH_DONE, { success: outcome.success, failed: outcome.failed });
      if (outcome.failed === 0) {
        toast.success(summary);
      } else {
        toast.error(summary);
      }
    }
  };

  const { exporting, progress, results } = batchExport;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2 text-base font-semibold">
            <Download className="h-4 w-4" />
            {t(I18N_KEYS.EXPLORER.EXPORT_WIZARD)}
          </DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-4">
          <p className="text-sm text-blue-600 dark:text-blue-400">
            {step === 1
              ? t(I18N_KEYS.EXPLORER.EXPORT_WIZARD_STEP1_HINT)
              : t(I18N_KEYS.EXPLORER.EXPORT_WIZARD_STEP2_HINT)}
          </p>

          {step === 1 ? (
            <>
              <div className="flex flex-col gap-2">
                <label className="text-sm font-medium theme-text-primary">
              {t(I18N_KEYS.EXPLORER.EXPORT_TARGET_DATABASE)}
            </label>
                <input
                  type="text"
                  readOnly
                  value={targetPath}
                  className="h-10 w-full rounded-md border border-gray-300 bg-gray-100 px-3 text-sm text-gray-500 focus:outline-none dark:border-gray-700 dark:bg-gray-800 dark:text-gray-400"
                />
              </div>
              <div className="flex flex-col gap-2">
                <label className="text-sm font-medium theme-text-primary">
                  {t(I18N_KEYS.EXPLORER.EXPORT_FILE_TYPE)}
                </label>
                <select
                  value={fileType}
                  onChange={(e) => setFileType(e.target.value)}
                  className="h-10 w-full rounded-md border border-gray-300 bg-white px-3 text-sm text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/40 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
                >
                  {EXPORT_FILE_TYPES.map((type) => (
                    <option key={type} value={type}>
                      {type}
                    </option>
                  ))}
                </select>
              </div>
              <div className="flex flex-col gap-2">
                <div className="flex items-center justify-between">
                  <label className="text-sm font-medium theme-text-primary">
                    {t(I18N_KEYS.EXPLORER.EXPORT_SELECT_TABLES)}
                  </label>
                  <div className="flex items-center gap-1">
                    <Button
                      variant="outline"
                      className="h-7 px-2 text-xs"
                      disabled={tablesLoading}
                      onClick={() => setSelectedTables(new Set(tables))}
                    >
                      {t(I18N_KEYS.EXPLORER.EXPORT_SELECT_ALL)}
                    </Button>
                    <Button
                      variant="outline"
                      className="h-7 px-2 text-xs"
                      disabled={tablesLoading}
                      onClick={() => setSelectedTables(new Set())}
                    >
                      {t(I18N_KEYS.EXPLORER.EXPORT_DESELECT_ALL)}
                    </Button>
                  </div>
                </div>
                <input
                  type="text"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder={t(I18N_KEYS.EXPLORER.EXPORT_SEARCH_TABLES)}
                  disabled={tablesLoading}
                  className="h-9 w-full rounded-md border border-gray-300 bg-white px-3 text-sm text-gray-900 placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-primary/40 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
                />
                <div className="h-60 overflow-y-auto rounded-md border border-gray-300 bg-white px-3 py-2 dark:border-gray-700 dark:bg-gray-900">
                  {tablesLoading ? (
                    <div className="flex h-full items-center justify-center">
                      <Loader2 className="h-4 w-4 animate-spin text-gray-400" />
                    </div>
                  ) : tablesLoadFailed ? (
                    <div className="flex h-full items-center justify-center text-xs text-red-500">
                      {t(I18N_KEYS.EXPLORER.EXPORT_TABLES_LOAD_FAILED)}
                    </div>
                  ) : filteredTables.length === 0 ? (
                    <div className="flex h-full items-center justify-center text-xs text-gray-500 dark:text-gray-400">
                      {t(I18N_KEYS.COMMON.NO_DATA)}
                    </div>
                  ) : (
                  <>
                    <div className="flex items-center justify-between border-b border-gray-200 pb-1 text-xs font-medium text-gray-500 dark:border-gray-700 dark:text-gray-400">
                      <span>{t(I18N_KEYS.EXPLORER.EXPORT_SOURCE_TABLE)}</span>
                      <span>{t(I18N_KEYS.EXPLORER.EXPORT_EXPORT_TO)}</span>
                    </div>
                    {filteredTables.map((name) => (
                      <div
                        key={name}
                        className="flex items-center justify-between rounded px-1 py-0.5 hover:bg-gray-100 dark:hover:bg-gray-800"
                      >
                        <label className="flex min-w-0 flex-1 cursor-pointer items-center gap-2 text-sm text-gray-900 dark:text-gray-100">
                          <input
                            type="checkbox"
                            checked={selectedTables.has(name)}
                            onChange={() => handleToggleTable(name)}
                            className="h-3.5 w-3.5 accent-blue-600"
                          />
                          <span className="truncate">{name}</span>
                        </label>
                        <span className="ml-2 max-w-[45%] truncate text-xs text-gray-500 dark:text-gray-400">
                          {name}.{fileExtension}
                        </span>
                      </div>
                    ))}
                  </>
                )}
                </div>
                <div className="flex items-center gap-2 text-xs">
                  <span
                    className={
                      selectedCount === 0 ? 'text-red-500' : 'text-gray-500 dark:text-gray-400'
                    }
                  >
                    {t(I18N_KEYS.EXPLORER.EXPORT_TABLES_SELECTED, {
                      selected: selectedCount,
                      total: tables.length,
                    })}
                  </span>
                </div>
              </div>
            </>
          ) : (
            <div className="flex flex-col gap-2">
              <div className="flex items-center gap-2">
                <label className="text-sm font-medium theme-text-primary">
                  {t(I18N_KEYS.EXPLORER.EXPORT_SOURCE_TABLE)}
                </label>
                <select
                  value={activeSourceTable}
                  onChange={(e) => handleSourceTableChange(e.target.value)}
                  disabled={activeFieldsLoading}
                  className="h-9 flex-1 rounded-md border border-gray-300 bg-white px-3 text-sm text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/40 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
                >
                  {queuedTables.map((name) => (
                    <option key={name} value={name}>
                      {name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="flex items-center justify-between">
                <label className="text-sm font-medium theme-text-primary">
                  {t(I18N_KEYS.EXPLORER.EXPORT_AVAILABLE_FIELDS)}
                </label>
                <div className="flex items-center gap-1">
                  <Button
                    variant="outline"
                    className="h-7 px-2 text-xs"
                    disabled={activeFieldsLoading || activeFields.length === 0}
                    onClick={() =>
                      setFieldSelections((previous) => ({
                        ...previous,
                        [activeSourceTable]: new Set(activeFields),
                      }))
                    }
                  >
                    {t(I18N_KEYS.EXPLORER.EXPORT_SELECT_ALL)}
                  </Button>
                  <Button
                    variant="outline"
                    className="h-7 px-2 text-xs"
                    disabled={activeFieldsLoading || activeFields.length === 0}
                    onClick={() =>
                      setFieldSelections((previous) => ({
                        ...previous,
                        [activeSourceTable]: new Set<string>(),
                      }))
                    }
                  >
                    {t(I18N_KEYS.EXPLORER.EXPORT_DESELECT_ALL)}
                  </Button>
                </div>
              </div>
              <div className="h-60 overflow-y-auto rounded-md border border-gray-300 bg-white px-3 py-2 dark:border-gray-700 dark:bg-gray-900">
                {activeFieldsLoading ? (
                  <div className="flex h-full items-center justify-center">
                    <Loader2 className="h-4 w-4 animate-spin text-gray-400" />
                  </div>
                ) : activeFields.length === 0 ? (
                  <div className="flex h-full items-center justify-center text-xs text-gray-500 dark:text-gray-400">
                    {t(I18N_KEYS.EXPLORER.EXPORT_FIELDS_LOAD_FALLBACK)}
                  </div>
                ) : (
                  activeFields.map((name) => (
                    <label
                      key={name}
                      className="flex cursor-pointer items-center gap-2 rounded px-1 py-0.5 text-sm text-gray-900 hover:bg-gray-100 dark:text-gray-100 dark:hover:bg-gray-800"
                    >
                      <input
                        type="checkbox"
                        checked={activeSelection ? activeSelection.has(name) : true}
                        onChange={() => handleToggleField(name)}
                        className="h-3.5 w-3.5 accent-blue-600"
                      />
                      <span className="truncate">{name}</span>
                    </label>
                  ))
                )}
              </div>
              <div className="flex items-center gap-2 text-xs">
                <span
                  className={
                    activeSelectedCount === 0 ? 'text-red-500' : 'text-gray-500 dark:text-gray-400'
                  }
                >
                  {t(I18N_KEYS.EXPLORER.EXPORT_FIELDS_SELECTED, {
                    selected: activeSelectedCount,
                    total: activeFields.length,
                  })}
                </span>
                {hasEmptySelection && (
                  <span className="text-red-500">
                    {t(I18N_KEYS.EXPLORER.EXPORT_SELECT_AT_LEAST_ONE)}
                  </span>
                )}
              </div>
            </div>
          )}

          {progress && (
            <div className="flex items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
              {t(I18N_KEYS.EXPLORER.EXPORT_BATCH_PROGRESS, {
                current: progress.current,
                total: progress.total,
                table: progress.table,
              })}
            </div>
          )}
          {results.length > 0 && (
            <div className="max-h-32 space-y-1 overflow-y-auto rounded-md border border-gray-300 bg-white p-2 text-xs dark:border-gray-700 dark:bg-gray-900">
              {results.map((result) => (
                <div key={result.table} className="flex items-start gap-1">
                  {result.success ? (
                    <span className="text-green-600 dark:text-green-500">&#10003;</span>
                  ) : (
                    <span className="text-red-500">&#10007;</span>
                  )}
                  <span className="shrink-0 text-gray-900 dark:text-gray-100">{result.table}</span>
                  {!result.success && result.message && (
                    <span className="min-w-0 flex-1 break-all text-red-500">{result.message}</span>
                  )}
                </div>
              ))}
            </div>
          )}

          <div className="flex justify-end gap-2">
            {exporting ? (
              <Button variant="outline" onClick={() => batchExport.stop()}>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                {t(I18N_KEYS.EXPLORER.EXPORT_BATCH_STOP)}
              </Button>
            ) : (
              <>
                <Button
                  variant="outline"
                  onClick={() => handleOpenChange(false)}
                >
                  {t(I18N_KEYS.CONNECTIONS.CANCEL)}
                </Button>
                {step === 2 && (
                  <Button variant="outline" onClick={() => setStep(1)}>
                    {t(I18N_KEYS.EXPLORER.EXPORT_WIZARD_PREV)}
                  </Button>
                )}
                {step === 1 ? (
                  <Button
                    onClick={handleGoToStep2}
                    disabled={tablesLoading || tablesLoadFailed || selectedCount === 0}
                  >
                    {t(I18N_KEYS.EXPLORER.EXPORT_WIZARD_NEXT)}
                  </Button>
                ) : (
                  <Button
                    onClick={() => void handleBatchExport()}
                    disabled={!exportParams || selectedCount === 0 || hasEmptySelection}
                  >
                    <Download className="mr-2 h-4 w-4" />
                    {t(I18N_KEYS.EXPLORER.EXPORT_DATA)}
                  </Button>
                )}
              </>
            )}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
