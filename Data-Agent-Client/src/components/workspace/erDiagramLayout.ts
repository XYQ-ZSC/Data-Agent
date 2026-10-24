import ELK from 'elkjs/lib/elk.bundled.js';
import type { ElkNode } from 'elkjs/lib/elk-api';
import type { ErRelation, ErTable } from '../../services/table.service';
import { columnKey, relationFromId, relationToId, tableId } from './erDiagramRelations';

export const CARD_WIDTH = 260;
export const HEADER_HEIGHT = 34;
export const ROW_HEIGHT = 22;
export const MAX_VISIBLE_ROWS = 12;
export const EXPAND_BAR_HEIGHT = 22;
const CARD_BORDER = 2;

const elk = new ELK();

export function cardHeight(columnCount: number, expanded = false) {
  const overflow = columnCount > MAX_VISIBLE_ROWS;
  const rows = expanded || !overflow ? Math.max(columnCount, 1) : MAX_VISIBLE_ROWS;
  return HEADER_HEIGHT + rows * ROW_HEIGHT + (overflow ? EXPAND_BAR_HEIGHT : 0) + CARD_BORDER;
}

/** Keeps linked rows on the card. Extra fields stay behind the expand bar. */
export function visibleColumns<T extends { name: string }>(
  columns: T[],
  linked: ReadonlySet<string>,
  highlighted: ReadonlySet<string>,
  tableKey: string,
  expanded: boolean,
): T[] {
  if (expanded || columns.length <= MAX_VISIBLE_ROWS) return columns;
  const chosen = new Set<string>();
  const take = (want: (column: T) => boolean) => {
    columns.forEach((column) => {
      if (chosen.size >= MAX_VISIBLE_ROWS || chosen.has(column.name)) return;
      if (want(column)) chosen.add(column.name);
    });
  };
  take((column) => highlighted.has(columnKey(tableKey, column.name)));
  take((column) => linked.has(columnKey(tableKey, column.name)));
  take(() => true);
  return columns.filter((column) => chosen.has(column.name));
}

export function toNodeId(tableKey: string) {
  return tableKey.split('\0').join('\u001f');
}

export function layoutStorageKey(connectionId: string | number, catalog?: string | null, schema?: string | null) {
  return `er-diagram-layout:${connectionId}:${catalog ?? ''}:${schema ?? ''}`;
}

export interface LayoutPoint {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

export type StoredLayout = Record<string, { x: number; y: number }>;

export interface LayoutStore {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

function defaultStore(): LayoutStore {
  return localStorage;
}

export function readStoredLayout(key: string, store: LayoutStore = defaultStore()): StoredLayout | null {
  const raw = store.getItem(key);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    const layout: StoredLayout = {};
    Object.entries(parsed as Record<string, unknown>).forEach(([id, value]) => {
      if (!value || typeof value !== 'object') return;
      const point = value as { x?: unknown; y?: unknown };
      if (typeof point.x !== 'number' || typeof point.y !== 'number') return;
      if (!Number.isFinite(point.x) || !Number.isFinite(point.y)) return;
      layout[id] = { x: point.x, y: point.y };
    });
    return layout;
  } catch {
    return null;
  }
}

export function writeStoredLayout(key: string, updates: StoredLayout, store: LayoutStore = defaultStore()) {
  const current = readStoredLayout(key, store) ?? {};
  store.setItem(key, JSON.stringify({ ...current, ...updates }));
}

export function clearStoredLayout(key: string, store: LayoutStore = defaultStore()) {
  store.removeItem(key);
}

export function hasStoredLayout(layout: StoredLayout | null) {
  return !!layout && Object.keys(layout).length > 0;
}

export function mergeStoredPositions(points: LayoutPoint[], stored: StoredLayout | null): LayoutPoint[] {
  if (!stored) return points;
  return points.map((point) => {
    const saved = stored[point.id];
    if (!saved) return point;
    return { ...point, x: saved.x, y: saved.y };
  });
}

function gridLayout(tables: ErTable[]): LayoutPoint[] {
  const columns = Math.max(1, Math.ceil(Math.sqrt(Math.max(tables.length, 1))));
  const heights = tables.map((table) => cardHeight(table.columns.length));
  const rowCount = Math.ceil(tables.length / columns);
  const rowHeights: number[] = [];
  for (let row = 0; row < rowCount; row += 1) {
    let height = 0;
    for (let column = 0; column < columns; column += 1) {
      height = Math.max(height, heights[row * columns + column] ?? 0);
    }
    rowHeights.push(height);
  }
  return tables.map((table, index) => {
    const column = index % columns;
    const row = Math.floor(index / columns);
    const y = 24 + rowHeights.slice(0, row).reduce((sum, height) => sum + height + 56, 0);
    return {
      id: toNodeId(tableId(table)),
      x: 24 + column * (CARD_WIDTH + 72),
      y,
      width: CARD_WIDTH,
      height: heights[index],
    };
  });
}

export async function layoutDiagram(tables: ErTable[], relations: ErRelation[]): Promise<LayoutPoint[]> {
  if (tables.length === 0) return [];
  const points = tables.map((table) => ({
    id: toNodeId(tableId(table)),
    width: CARD_WIDTH,
    height: cardHeight(table.columns.length),
  }));
  const known = new Set(points.map((point) => point.id));
  const edges = relations.flatMap((relation, index) => {
    const source = toNodeId(relationFromId(relation));
    const target = toNodeId(relationToId(relation));
    if (!known.has(source) || !known.has(target) || source === target) return [];
    return [{ id: `e${index}`, sources: [source], targets: [target] }];
  });
  try {
    const graph = await elk.layout({
      id: 'root',
      layoutOptions: {
        'elk.algorithm': 'layered',
        'elk.direction': 'RIGHT',
        'elk.edgeRouting': 'ORTHOGONAL',
        'elk.spacing.nodeNode': '48',
        'elk.spacing.componentComponent': '48',
        'elk.layered.spacing.nodeNodeBetweenLayers': '100',
        'elk.padding': '[top=24,left=24,bottom=24,right=24]',
      },
      children: points.map((point) => ({
        id: point.id,
        width: point.width,
        height: point.height,
      })),
      edges,
    } satisfies ElkNode);
    const byId = new Map((graph.children ?? []).map((child) => [child.id, child]));
    return points.map((point) => {
      const placed = byId.get(point.id);
      return {
        ...point,
        x: placed?.x ?? 24,
        y: placed?.y ?? 24,
      };
    });
  } catch {
    return gridLayout(tables);
  }
}
