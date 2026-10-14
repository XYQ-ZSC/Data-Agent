import { describe, expect, it } from 'vitest';
import type { ErRelation, ErTable } from '../../services/table.service';
import { columnKey } from './erDiagramRelations';
import {
  cardHeight,
  clearStoredLayout,
  hasStoredLayout,
  layoutBounds,
  layoutDiagram,
  mergeStoredPositions,
  normalizeLayout,
  viewportForDiagram,
  readStoredLayout,
  toNodeId,
  visibleColumns,
  writeStoredLayout,
  type LayoutPoint,
  type LayoutStore,
} from './erDiagramLayout';

function memoryStore(initial: Record<string, string> = {}): LayoutStore {
  const data = { ...initial };
  return {
    getItem: (key) => (key in data ? data[key] : null),
    setItem: (key, value) => {
      data[key] = value;
    },
    removeItem: (key) => {
      delete data[key];
    },
  };
}

function table(name: string, columns = 2): ErTable {
  return {
    name,
    comment: '',
    columns: Array.from({ length: columns }, (_, index) => ({
      name: `c${index}`,
      typeName: 'int',
      comment: '',
      primaryKey: index === 0,
    })),
  };
}

function point(id: string, x: number, y: number): LayoutPoint {
  return { id, x, y, width: 260, height: 86 };
}

describe('ER diagram card height', () => {
  it('keeps a long table collapsed until that table is expanded', () => {
    expect(cardHeight(20)).toBe(34 + 12 * 22 + 22 + 2);
    expect(cardHeight(20, true)).toBeGreaterThan(cardHeight(20));
    expect(cardHeight(8)).toBe(34 + 8 * 22 + 2);
  });

  it('keeps a linked column that would otherwise sit below the fold', () => {
    const columns = Array.from({ length: 14 }, (_, index) => ({ name: `c${index}` }));
    const tableKey = '\0app\0users';
    const linked = new Set([columnKey(tableKey, 'c13')]);
    const shown = visibleColumns(columns, linked, new Set(), tableKey, false);
    expect(shown).toHaveLength(12);
    expect(shown.map((column) => column.name)).toContain('c13');
    expect(shown.map((column) => column.name)).not.toContain('c12');
  });
});

describe('ER diagram layout storage', () => {
  it('keeps a saved position and leaves the others on the computed layout', () => {
    const points = [point('users', 10, 20), point('orders', 400, 20)];
    const merged = mergeStoredPositions(points, { orders: { x: 12, y: 80 } });
    expect(merged.find((item) => item.id === 'users')).toMatchObject({ x: 10, y: 20 });
    expect(merged.find((item) => item.id === 'orders')).toMatchObject({ x: 12, y: 80 });
  });

  it('merges a drag so a hidden table keeps its saved position', () => {
    const store = memoryStore();
    writeStoredLayout('er', { users: { x: 1, y: 2 }, orders: { x: 3, y: 4 } }, store);
    writeStoredLayout('er', { users: { x: 9, y: 8 } }, store);
    expect(readStoredLayout('er', store)).toEqual({
      users: { x: 9, y: 8 },
      orders: { x: 3, y: 4 },
    });
    expect(hasStoredLayout(readStoredLayout('er', store))).toBe(true);
    clearStoredLayout('er', store);
    expect(readStoredLayout('er', store)).toBeNull();
  });

  it('uses an image-export-safe id and still reads a previously saved position', () => {
    const id = toNodeId('app\0dbo\0orders');
    expect(id).toBe('app|dbo|orders');
    expect(id).not.toMatch(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/);
    const legacy = 'app\u001fdbo\u001forders';
    const store = memoryStore({ er: JSON.stringify({ [legacy]: { x: 12, y: 40 } }) });
    expect(readStoredLayout('er', store)).toEqual({ [id]: { x: 12, y: 40 } });
  });

  it('ignores a stored value that is not a layout', () => {
    const store = memoryStore({ er: '{not json', bad: '[]', partial: '{"a":{"x":1}}' });
    expect(readStoredLayout('er', store)).toBeNull();
    expect(readStoredLayout('bad', store)).toBeNull();
    expect(readStoredLayout('partial', store)).toEqual({});
  });
});

describe('ER diagram opening frame', () => {
  it('anchors the laid-out cards at the top-left origin', () => {
    const shifted = normalizeLayout([
      point('users', 400, 660),
      point('orders', 80, 900),
    ]);
    expect(Math.min(...shifted.map((item) => item.x))).toBe(24);
    expect(Math.min(...shifted.map((item) => item.y))).toBe(24);
    expect(shifted[0].x - shifted[1].x).toBe(320);
    expect(shifted[0].y - shifted[1].y).toBe(-240);
  });

  it('fits a short diagram to the top of the window', () => {
    const bounds = layoutBounds([
      { x: 24, y: 24, width: 260, height: 80 },
      { x: 356, y: 24, width: 260, height: 120 },
    ]);
    const viewport = viewportForDiagram(bounds, 1200, 800);
    expect(viewport?.zoom).toBe(1);
    expect(viewport && viewport.y + bounds.y * viewport.zoom).toBe(32);
    expect(viewport && viewport.x + bounds.x * viewport.zoom).toBeGreaterThan(32);
  });

  it('shrinks a diagram that would otherwise run past the window', () => {
    const bounds = { x: 24, y: 24, width: 900, height: 1400 };
    const viewport = viewportForDiagram(bounds, 1000, 700);
    expect(viewport).toBeTruthy();
    const zoom = viewport!.zoom;
    expect(zoom).toBeLessThan(1);
    const top = viewport!.y + bounds.y * zoom;
    const bottom = viewport!.y + (bounds.y + bounds.height) * zoom;
    const left = viewport!.x + bounds.x * zoom;
    const right = viewport!.x + (bounds.x + bounds.width) * zoom;
    expect(top).toBeCloseTo(32);
    expect(bottom).toBeLessThanOrEqual(700 - 32 + 0.01);
    expect(left).toBeGreaterThanOrEqual(32 - 0.01);
    expect(right).toBeLessThanOrEqual(1000 - 32 + 0.01);
  });
});

describe('ELK diagram layout', () => {
  it('places a child table to the side of the table it references', async () => {
    const users = table('users');
    const orders = table('orders');
    const relation: ErRelation = {
      name: 'fk_user',
      fromTable: 'orders',
      fromColumn: 'user_id',
      fromColumns: ['user_id'],
      toTable: 'users',
      toColumn: 'id',
      toColumns: ['id'],
      cardinality: 'ONE_TO_MANY',
    };
    const points = await layoutDiagram([users, orders], [relation]);
    expect(points).toHaveLength(2);
    const left = points.find((item) => item.id.endsWith('users'));
    const right = points.find((item) => item.id.endsWith('orders'));
    expect(left && right).toBeTruthy();
    expect(Number.isFinite(left!.x) && Number.isFinite(right!.x)).toBe(true);
    expect(left!.x).not.toBe(right!.x);
  });
});
