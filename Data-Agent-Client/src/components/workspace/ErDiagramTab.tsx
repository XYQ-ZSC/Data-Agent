import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Background,
  BackgroundVariant,
  ReactFlow,
  ReactFlowProvider,
  getNodesBounds,
  getViewportForBounds,
  useEdgesState,
  useNodesState,
  useReactFlow,
} from '@xyflow/react';
import { toPng } from 'html-to-image';
import '@xyflow/react/dist/style.css';
import { I18N_KEYS } from '../../constants/i18nKeys';
import { tableService, type ErDiagram, type ErTable } from '../../services/table.service';
import { useWorkspaceStore } from '../../store/workspaceStore';
import { TableDblClickConsoleTargetEnum } from '../../constants/workspacePreferences';
import type { ConsoleTabMetadata, ErDiagramTabMetadata } from '../../types/tab';
import { useToast } from '../../hooks/useToast';
import { downloadDataUrl, erDiagramFilename } from './erDiagramExport';
import { ErRelationEdgeView } from './ErDiagramEdge';
import { ErTableNode, type ErTableNodeType } from './ErDiagramNode';
import { buildDiagramEdges, ER_ROSE, ER_VIOLET, type ErRelationEdge } from './erDiagramEdges';
import { ErDiagramInteractionContext, ErDiagramRectsContext } from './erDiagramInteraction';
import {
  CARD_WIDTH,
  cardHeight,
  clearStoredLayout,
  hasStoredLayout,
  layoutDiagram,
  layoutStorageKey,
  mergeStoredPositions,
  readStoredLayout,
  toNodeId,
  writeStoredLayout,
  type LayoutPoint,
} from './erDiagramLayout';
import {
  cardinalityLabel,
  collapseJunctions,
  columnKey,
  filterTablesByName,
  junctionTableId,
  relationFromId,
  relationMapping,
  relationToId,
  relationTouches,
  sideColumns,
  tableId,
  viaTableName,
  type ColumnFocus,
  type EndMark,
} from './erDiagramRelations';
import type { DiagramRect } from './erDiagramRoute';

const nodeTypes = { erTable: ErTableNode };
const edgeTypes = { er: ErRelationEdgeView };

function LegendSample({ mark, label, color, dashed }: { mark?: EndMark; label: string; color?: string; dashed?: boolean }) {
  const stroke = color ?? 'currentColor';
  return (
    <span className="inline-flex items-center gap-1">
      <svg width="26" height="12" aria-hidden="true">
        {mark === 'many' ? (
          <path d="M1 6 L8 1 M1 6 H25 M1 6 L8 11" fill="none" stroke={stroke} strokeWidth="1.4" strokeDasharray={dashed ? '3 2' : undefined} />
        ) : mark === 'one' ? (
          <path d="M2 1 V11 M6 6 H25" fill="none" stroke={stroke} strokeWidth="1.4" />
        ) : (
          <path d="M1 6 H25" fill="none" stroke={stroke} strokeWidth="1.4" strokeDasharray="3 2" />
        )}
      </svg>
      <span>{label}</span>
    </span>
  );
}

interface ErDiagramTabProps {
  metadata: ErDiagramTabMetadata;
}

function tableLocation(table: ErTable, metadata: ErDiagramTabMetadata) {
  return {
    catalog: table.catalog || metadata.catalog || '',
    schema: table.schema || metadata.schema || '',
  };
}

function toFlowNodes(tables: ErTable[], points: LayoutPoint[], previous: ErTableNodeType[] = []): ErTableNodeType[] {
  const byId = new Map(points.map((point) => [point.id, point]));
  const expandedIds = new Set(previous.filter((node) => node.data.expanded).map((node) => node.id));
  return tables.map((table) => {
    const id = toNodeId(tableId(table));
    const point = byId.get(id);
    const expanded = expandedIds.has(id);
    return {
      id,
      type: 'erTable',
      position: { x: point?.x ?? 24, y: point?.y ?? 24 },
      width: point?.width ?? CARD_WIDTH,
      height: cardHeight(table.columns.length, expanded),
      data: { table, expanded },
      dragHandle: '.er-drag-handle',
      selectable: false,
      deletable: false,
    };
  });
}

export function ErDiagramTab({ metadata }: ErDiagramTabProps) {
  return (
    <ReactFlowProvider>
      <ErDiagramCanvas metadata={metadata} />
    </ReactFlowProvider>
  );
}

function ErDiagramCanvas({ metadata }: ErDiagramTabProps) {
  const { t } = useTranslation();
  const toast = useToast();
  const openTab = useWorkspaceStore((state) => state.openTab);
  const tabs = useWorkspaceStore((state) => state.tabs);
  const updateTabContent = useWorkspaceStore((state) => state.updateTabContent);
  const updateTabMetadata = useWorkspaceStore((state) => state.updateTabMetadata);
  const switchTab = useWorkspaceStore((state) => state.switchTab);
  const tableDblClickConsoleTarget = useWorkspaceStore((state) => state.tableDblClickConsoleTarget);
  const { fitView, getNodes } = useReactFlow();
  const [diagram, setDiagram] = useState<ErDiagram | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [hover, setHoverState] = useState<ColumnFocus | null>(null);
  const [pinned, setPinned] = useState<ColumnFocus | null>(null);
  const [collapseJunctionTables, setCollapseJunctionTables] = useState(false);
  const [showAllLines, setShowAllLines] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [layouting, setLayouting] = useState(false);
  const [hasCustomLayout, setHasCustomLayout] = useState(false);
  const [tableNameFilter, setTableNameFilter] = useState('');
  const [exactTableFilter, setExactTableFilter] = useState(false);
  const [nodes, setNodes, onNodesChange] = useNodesState<ErTableNodeType>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<ErRelationEdge>([]);
  const draggingRef = useRef(false);
  const flowRef = useRef<HTMLDivElement>(null);
  const requestId = useRef(0);
  const fittedKey = useRef('');
  const storageKey = layoutStorageKey(metadata.connectionId, metadata.catalog, metadata.schema);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    setHoverState(null);
    setPinned(null);
    setCollapseJunctionTables(false);
    setShowAllLines(false);
    setTableNameFilter('');
    setExactTableFilter(false);
    tableService.getErDiagram(
      String(metadata.connectionId),
      metadata.catalog ?? undefined,
      metadata.schema ?? undefined,
    ).then((result) => {
      if (!cancelled) setDiagram(result);
    }).catch((err: unknown) => {
      if (!cancelled) {
        setDiagram(null);
        setError(err instanceof Error ? err.message : t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FAILED));
      }
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [metadata.catalog, metadata.connectionId, metadata.schema, t]);

  const junctionTables = useMemo(() => {
    const tables = new Set<string>();
    diagram?.relations.forEach((relation) => {
      const id = junctionTableId(relation);
      if (id) tables.add(id);
    });
    return tables;
  }, [diagram]);
  const collapsed = useMemo(
    () => (diagram ? collapseJunctions(diagram, collapseJunctionTables) : null),
    [collapseJunctionTables, diagram],
  );
  const shown = useMemo(
    () => (collapsed ? filterTablesByName(collapsed, tableNameFilter, exactTableFilter) : null),
    [collapsed, exactTableFilter, tableNameFilter],
  );

  useEffect(() => {
    const visible = new Set(shown?.tables.map((table) => tableId(table)) ?? []);
    setHoverState((current) => (current && !visible.has(current.tableId) ? null : current));
    setPinned((current) => (current && !visible.has(current.tableId) ? null : current));
  }, [shown]);

  const placeNodes = useCallback((next: ErDiagram, useStored: boolean) => {
    const token = ++requestId.current;
    setLayouting(true);
    return layoutDiagram(next.tables, next.relations).then((points) => {
      if (token !== requestId.current) return;
      const stored = useStored ? readStoredLayout(storageKey) : null;
      setHasCustomLayout(hasStoredLayout(stored));
      setNodes(toFlowNodes(next.tables, mergeStoredPositions(points, stored), getNodes() as ErTableNodeType[]));
    }).finally(() => {
      if (token === requestId.current) setLayouting(false);
    });
  }, [getNodes, setNodes, storageKey]);

  useEffect(() => {
    if (!shown || shown.tables.length === 0) {
      requestId.current += 1;
      setNodes([]);
      setLayouting(false);
      return;
    }
    void placeNodes(shown, true);
  }, [placeNodes, setNodes, shown]);

  const linkedColumns = useMemo(() => {
    const linked = new Set<string>();
    shown?.relations.forEach((relation) => {
      sideColumns(relation, 'from').forEach((column) => linked.add(columnKey(relationFromId(relation), column)));
      sideColumns(relation, 'to').forEach((column) => linked.add(columnKey(relationToId(relation), column)));
    });
    return linked;
  }, [shown]);

  const active = hover ?? pinned;
  const activeRelations = useMemo(
    () => (shown && active ? shown.relations.filter((relation) => relationTouches(relation, active)) : []),
    [active, shown],
  );
  const highlightedColumns = useMemo(() => {
    const highlighted = new Set<string>();
    activeRelations.forEach((relation) => {
      sideColumns(relation, 'from').forEach((column) => highlighted.add(columnKey(relationFromId(relation), column)));
      sideColumns(relation, 'to').forEach((column) => highlighted.add(columnKey(relationToId(relation), column)));
    });
    return highlighted;
  }, [activeRelations]);

  const rects = useMemo<DiagramRect[]>(() => nodes.map((node) => ({
    id: node.id,
    x: node.position.x,
    y: node.position.y,
    width: node.width ?? CARD_WIDTH,
    height: cardHeight(node.data.table.columns.length, node.data.expanded),
  })), [nodes]);

  useEffect(() => {
    setEdges(buildDiagramEdges(
      nodes.map((node) => ({
        id: node.id,
        position: node.position,
        width: node.width,
        expanded: node.data.expanded,
        columns: node.data.table.columns,
        tableKey: tableId(node.data.table),
      })),
      shown?.relations ?? [],
      active,
      showAllLines,
      linkedColumns,
      highlightedColumns,
    ));
  }, [active, highlightedColumns, linkedColumns, nodes, setEdges, showAllLines, shown]);

  const togglePin = useCallback((focus: ColumnFocus) => {
    setPinned((current) => (
      current?.tableId === focus.tableId && current.column === focus.column ? null : focus
    ));
  }, []);
  const setHover = useCallback((focus: ColumnFocus) => {
    if (draggingRef.current) return;
    setHoverState(focus);
  }, []);
  const clearHover = useCallback((focus: ColumnFocus) => {
    if (draggingRef.current) return;
    setHoverState((current) => (
      current?.tableId === focus.tableId && current.column === focus.column ? null : current
    ));
  }, []);

  const openTableData = useCallback((table: ErTable) => {
    const { catalog, schema } = tableLocation(table, metadata);
    const connectionId = Number(metadata.connectionId);
    openTab({
      id: `table-${connectionId}-${catalog}-${schema}-${table.name}`,
      name: table.name,
      type: 'table',
      content: '',
      metadata: {
        connectionId,
        dbType: metadata.dbType,
        connectionName: metadata.connectionName,
        databaseName: catalog || schema || null,
        schemaName: schema || null,
        objectName: table.name,
        objectType: 'table',
        catalog: catalog || undefined,
        schema: schema || undefined,
      },
    });
  }, [metadata, openTab]);

  const openTableDdl = useCallback(async (table: ErTable) => {
    const { catalog, schema } = tableLocation(table, metadata);
    const connectionId = Number(metadata.connectionId);
    const consoleTabs = tabs.filter((tab) => (
      tab.type === 'file'
      && (tab.metadata as ConsoleTabMetadata | undefined)?.connectionId === connectionId
    ));
    const reuse = tableDblClickConsoleTarget === TableDblClickConsoleTargetEnum.REUSE && consoleTabs.length > 0;
    const tabId = reuse
      ? consoleTabs[0].id
      : `ddl-${connectionId}-${catalog}-${schema}-${table.name}`;
    const consoleMetadata = {
      connectionId,
      connectionName: metadata.connectionName,
      databaseName: catalog || schema || null,
      schemaName: schema || null,
      dbType: metadata.dbType,
    };
    if (reuse) {
      updateTabMetadata(tabId, consoleMetadata);
      switchTab(tabId);
    } else {
      openTab({
        id: tabId,
        name: table.name,
        type: 'file',
        content: '',
        metadata: consoleMetadata,
      });
    }
    try {
      const ddl = await tableService.getTableDdl(String(connectionId), table.name, catalog, schema);
      updateTabContent(tabId, ddl);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : t(I18N_KEYS.EXPLORER.LOAD_DDL_FAILED);
      updateTabContent(tabId, `-- ${message}\n`);
      toast.error(message);
    }
  }, [metadata, openTab, switchTab, t, tableDblClickConsoleTarget, tabs, toast, updateTabContent, updateTabMetadata]);

  const openExternalErDiagram = useCallback((table: ErTable) => {
    if (!table.external) return;
    const catalog = table.catalog || '';
    const schema = table.schema || '';
    if (catalog === (metadata.catalog || '') && schema === (metadata.schema || '')) return;
    const scope = schema || catalog;
    if (!scope) return;
    const connectionId = Number(metadata.connectionId);
    openTab({
      id: `er-${connectionId}-${catalog}-${schema}`,
      name: `ER ${scope}`,
      type: 'er',
      content: '',
      metadata: {
        connectionId,
        dbType: metadata.dbType,
        connectionName: metadata.connectionName,
        databaseName: catalog || schema || null,
        schemaName: schema || null,
        catalog: catalog || undefined,
        schema: schema || undefined,
      },
    });
  }, [metadata, openTab]);

  const interaction = useMemo(() => ({
    linked: linkedColumns,
    highlighted: highlightedColumns,
    junctionTables,
    active,
    draggingRef,
    togglePin,
    setHover,
    clearHover,
    openData: openTableData,
    openDdl: openTableDdl,
    openExternal: openExternalErDiagram,
  }), [
    active,
    clearHover,
    highlightedColumns,
    junctionTables,
    linkedColumns,
    openExternalErDiagram,
    openTableData,
    openTableDdl,
    setHover,
    togglePin,
  ]);

  const fitToWindow = useCallback(() => {
    void fitView({ padding: '32px', maxZoom: 1, minZoom: 0.08, duration: 0 });
  }, [fitView]);

  useEffect(() => {
    if (layouting || nodes.length === 0) return;
    const key = nodes.map((node) => node.id).sort().join('\n');
    if (fittedKey.current === key) return;
    fittedKey.current = key;
    const frame = requestAnimationFrame(() => fitToWindow());
    return () => cancelAnimationFrame(frame);
  }, [fitToWindow, layouting, nodes]);

  const resetLayout = () => {
    if (!shown || shown.tables.length === 0) return;
    fittedKey.current = '';
    clearStoredLayout(storageKey);
    void placeNodes(shown, false);
  };

  const onNodeDragStart = () => {
    draggingRef.current = true;
    setHoverState(null);
  };

  const onNodeDragStop = (_event: unknown, node: ErTableNodeType) => {
    draggingRef.current = false;
    const updates: Record<string, { x: number; y: number }> = {};
    getNodes().forEach((item) => {
      const position = item.id === node.id ? node.position : item.position;
      updates[item.id] = { x: position.x, y: position.y };
    });
    writeStoredLayout(storageKey, updates);
    setHasCustomLayout(true);
  };

  const exportImage = async () => {
    const viewport = flowRef.current?.querySelector('.react-flow__viewport');
    if (!(viewport instanceof HTMLElement) || exporting) return;
    const currentNodes = getNodes();
    if (currentNodes.length === 0) return;
    const bounds = getNodesBounds(currentNodes);
    if (bounds.width <= 0 || bounds.height <= 0) return;
    const imageWidth = Math.max(1, Math.min(8192, Math.ceil(bounds.width + 96)));
    const imageHeight = Math.max(1, Math.min(8192, Math.ceil(bounds.height + 96)));
    const fitted = getViewportForBounds(bounds, imageWidth, imageHeight, 0.05, 1, '48px');
    const background = getComputedStyle(document.documentElement).getPropertyValue('--bg-main').trim() || '#f4f7fb';
    setExporting(true);
    try {
      const dataUrl = await toPng(viewport, {
        backgroundColor: background,
        width: imageWidth,
        height: imageHeight,
        pixelRatio: 1,
        style: {
          width: `${imageWidth}px`,
          height: `${imageHeight}px`,
          transform: `translate(${fitted.x}px, ${fitted.y}px) scale(${fitted.zoom})`,
        },
      });
      const name = metadata.databaseName || metadata.schemaName || metadata.connectionName || 'er-diagram';
      downloadDataUrl(dataUrl, erDiagramFilename(name));
      toast.success(t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORTED));
    } catch {
      toast.error(t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT_FAILED));
    } finally {
      setExporting(false);
    }
  };

  const relationSummary = activeRelations.map((relation) => {
    const via = viaTableName(relation);
    const text = `${relationMapping(relation)} ${cardinalityLabel(relation)}`;
    return via ? `${text} ${t(I18N_KEYS.EXPLORER.ER_DIAGRAM_VIA, { table: via })}` : text;
  }).join(' · ');
  const statusText = relationSummary || t(I18N_KEYS.EXPLORER.ER_DIAGRAM_HINT);
  const showCanvas = !loading && !error && !!shown && shown.tables.length > 0;

  return (
    <ErDiagramInteractionContext.Provider value={interaction}>
      <ErDiagramRectsContext.Provider value={rects}>
        <div className="flex h-full min-h-0 flex-col">
          <div className="shrink-0 border-b theme-border text-xs theme-text-secondary">
            <div className="flex min-h-10 flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2">
              <span className="theme-text-primary font-medium">{t(I18N_KEYS.EXPLORER.ER_DIAGRAM)}</span>
              <span>{metadata.databaseName || metadata.schemaName || metadata.connectionName}</span>
              {shown && (
                <span>
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_SUMMARY, {
                    tables: shown.tables.length,
                    relations: shown.relations.length,
                  })}
                </span>
              )}
              {diagram?.truncated && (
                <span>{t(I18N_KEYS.EXPLORER.ER_DIAGRAM_TRUNCATED, {
                  count: diagram.tables.filter((table) => !table.external).length,
                })}</span>
              )}
              {diagram?.externalTruncated && (
                <span>{t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXTERNAL_TRUNCATED, {
                  count: diagram.tables.filter((table) => table.external).length,
                })}</span>
              )}
              {junctionTables.size > 0 && (
                <label className="inline-flex items-center gap-1">
                  <input
                    type="checkbox"
                    className="accent-violet-600"
                    checked={collapseJunctionTables}
                    onChange={(event) => setCollapseJunctionTables(event.target.checked)}
                  />
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_COLLAPSE_JUNCTIONS)}
                </label>
              )}
              {diagram && diagram.tables.length > 0 && (
                <>
                  <input
                    type="search"
                    value={tableNameFilter}
                    onChange={(event) => setTableNameFilter(event.target.value)}
                    placeholder={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FILTER)}
                    aria-label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FILTER)}
                    className="h-6 w-52 rounded border theme-border bg-transparent px-2 text-[11px] theme-text-primary outline-none"
                  />
                  <label className="inline-flex items-center gap-1">
                    <input
                      type="checkbox"
                      className="accent-violet-600"
                      checked={exactTableFilter}
                      onChange={(event) => setExactTableFilter(event.target.checked)}
                    />
                    {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FILTER_EXACT)}
                  </label>
                </>
              )}
              {showCanvas && (
                <button
                  type="button"
                  aria-pressed={showAllLines}
                  className={`rounded border px-2 py-0.5 disabled:opacity-40 ${showAllLines ? 'border-rose-500 text-rose-600' : 'theme-border theme-text-primary'}`}
                  disabled={!shown || shown.relations.length === 0}
                  onClick={() => setShowAllLines((current) => !current)}
                >
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_SHOW_ALL_LINES)}
                </button>
              )}
              {showCanvas && (
                <button type="button" className="rounded border theme-border px-2 py-0.5 theme-text-primary" onClick={fitToWindow}>
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FIT)}
                </button>
              )}
              {showCanvas && (
                <button
                  type="button"
                  className="rounded border theme-border px-2 py-0.5 theme-text-primary disabled:opacity-40"
                  disabled={exporting || nodes.length === 0}
                  onClick={() => { void exportImage(); }}
                >
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EXPORT)}
                </button>
              )}
              {showCanvas && (
                <button
                  type="button"
                  className="rounded border theme-border px-2 py-0.5 theme-text-primary disabled:opacity-40"
                  disabled={!hasCustomLayout}
                  onClick={resetLayout}
                >
                  {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_RESET_LAYOUT)}
                </button>
              )}
              {showCanvas && (
                <span className="inline-flex flex-wrap items-center gap-x-3 gap-y-1 border-l theme-border pl-3">
                  <LegendSample mark="one" label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_LEGEND_ONE)} color={ER_ROSE} />
                  <LegendSample mark="many" label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_LEGEND_MANY)} color={ER_ROSE} />
                  <LegendSample mark="many" label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_LEGEND_MANY_TO_MANY)} color={ER_VIOLET} />
                  <LegendSample label={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_LEGEND_CROSS)} dashed />
                </span>
              )}
            </div>
            <div className="flex h-7 min-w-0 items-center overflow-hidden border-t theme-border px-3" title={statusText}>
              {activeRelations.length > 0 ? (
                <span className="min-w-0 truncate theme-text-primary">
                  {activeRelations.map((relation, index) => {
                    const via = viaTableName(relation);
                    return (
                      <span key={`${relationMapping(relation)}-${index}`}>
                        {index > 0 ? <span className="px-1.5 theme-text-secondary">·</span> : null}
                        {relationMapping(relation)}
                        {' '}
                        <span className={relation.cardinality === 'MANY_TO_MANY' ? 'text-violet-600' : undefined}>
                          {cardinalityLabel(relation)}
                        </span>
                        {via ? ` ${t(I18N_KEYS.EXPLORER.ER_DIAGRAM_VIA, { table: via })}` : ''}
                      </span>
                    );
                  })}
                </span>
              ) : (
                <span className="min-w-0 truncate">{statusText}</span>
              )}
            </div>
          </div>
          <div className="relative min-h-0 flex-1">
            {loading && (
              <div className="absolute inset-0 z-10 flex items-center justify-center text-sm theme-text-secondary">
                {t(I18N_KEYS.EXPLORER.LOADING)}
              </div>
            )}
            {!loading && error && (
              <div className="absolute inset-0 flex items-center justify-center px-6 text-sm text-destructive">
                {error}
              </div>
            )}
            {!loading && diagram && diagram.tables.length === 0 && (
              <div className="absolute inset-0 flex items-center justify-center text-sm theme-text-secondary">
                {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_EMPTY)}
              </div>
            )}
            {!loading && diagram && diagram.tables.length > 0 && shown && shown.tables.length === 0 && (
              <div className="absolute inset-0 flex items-center justify-center text-sm theme-text-secondary">
                {t(I18N_KEYS.EXPLORER.ER_DIAGRAM_FILTER_EMPTY)}
              </div>
            )}
            {showCanvas && (
              <div ref={flowRef} className="er-diagram-flow h-full min-h-0">
                <ReactFlow
                  nodes={nodes}
                  edges={edges}
                  onNodesChange={onNodesChange}
                  onEdgesChange={onEdgesChange}
                  nodeTypes={nodeTypes}
                  edgeTypes={edgeTypes}
                  onNodeDragStart={onNodeDragStart}
                  onNodeDragStop={onNodeDragStop}
                  minZoom={0.08}
                  maxZoom={1.8}
                  deleteKeyCode={null}
                  nodesConnectable={false}
                  elementsSelectable={false}
                  zoomOnDoubleClick={false}
                  className="theme-bg-main"
                  style={{ backgroundColor: 'var(--bg-main)' }}
                >
                  <Background variant={BackgroundVariant.Dots} gap={18} size={1} color="var(--border-color)" />
                </ReactFlow>
                {layouting && nodes.length === 0 && (
                  <div className="pointer-events-none absolute inset-0 flex items-center justify-center text-sm theme-text-secondary">
                    {t(I18N_KEYS.EXPLORER.LOADING)}
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      </ErDiagramRectsContext.Provider>
    </ErDiagramInteractionContext.Provider>
  );
}
