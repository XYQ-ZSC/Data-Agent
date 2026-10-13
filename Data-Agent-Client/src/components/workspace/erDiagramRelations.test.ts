import { describe, expect, it } from 'vitest';
import type { ErDiagram, ErRelation, ErTable } from '../../services/table.service';
import {
  anchorColumns,
  cardinalityLabel,
  collapseJunctions,
  endMarks,
  fitDiagramView,
  filterTablesByName,
  junctionTableId,
  zoomDiagramView,
  relationMapping,
  relationTouches,
  sideColumns,
  viaTableName,
} from './erDiagramRelations';

function relation(overrides: Partial<ErRelation> = {}): ErRelation {
  return {
    name: 'fk_user',
    fromTable: 'orders',
    fromColumn: 'user_id',
    fromColumns: ['user_id'],
    toTable: 'users',
    toColumn: 'id',
    toColumns: ['id'],
    cardinality: 'ONE_TO_MANY',
    ...overrides,
  };
}

describe('ER relation marks', () => {
  it('marks a foreign key as many on the child and one on the parent', () => {
    expect(endMarks(relation())).toEqual({ from: 'many', to: 'one' });
    expect(cardinalityLabel(relation())).toBe('1:N');
  });

  it('marks both ends as one when the foreign key is unique', () => {
    const one = relation({ cardinality: 'ONE_TO_ONE' });
    expect(endMarks(one)).toEqual({ from: 'one', to: 'one' });
    expect(cardinalityLabel(one)).toBe('1:1');
  });

  it('marks both referenced tables as many and names the junction', () => {
    const many = relation({
      cardinality: 'MANY_TO_MANY',
      fromTable: 'users',
      fromColumn: 'id',
      fromColumns: ['id'],
      toTable: 'roles',
      toColumn: 'id',
      toColumns: ['id'],
      viaSchema: 'app',
      viaTable: 'user_roles',
    });
    expect(endMarks(many)).toEqual({ from: 'many', to: 'many' });
    expect(cardinalityLabel(many)).toBe('N:M');
    expect(relationMapping(many)).toBe('users.id ↔ roles.id');
    expect(viaTableName(many)).toBe('app.user_roles');
    expect(junctionTableId(many)).toContain('user_roles');
  });

  it('treats every column of a composite foreign key as the same relation', () => {
    const composite = relation({
      fromSchema: 'app',
      toSchema: 'dict',
      fromColumn: 'user_id',
      fromColumns: ['user_id', 'tenant_id'],
      toColumn: 'id',
      toColumns: ['id', 'tenant_id'],
    });
    const focus = { tableId: '\0app\0orders', column: 'tenant_id' };
    expect(relationTouches(composite, focus)).toBe(true);
    expect(anchorColumns(composite, focus)).toEqual({ from: 'tenant_id', to: 'tenant_id' });
    expect(sideColumns(composite, 'to')).toEqual(['id', 'tenant_id']);
    expect(relationMapping(composite)).toBe('app.orders.(user_id, tenant_id) → dict.users.(id, tenant_id)');
  });

  it('hides an association table and its foreign keys while keeping the many-to-many link', () => {
    const source = sampleDiagram();
    expect(collapseJunctions(source, false)).toBe(source);

    const folded = collapseJunctions(source, true);
    expect(folded.tables.map((item) => item.name)).toEqual(['users', 'roles', 'orders']);
    expect(folded.relations.map((item) => item.name)).toEqual(['user_roles', 'fk_order']);
    expect(folded.relations[0].cardinality).toBe('MANY_TO_MANY');
    expect(folded.relations[1].cardinality).toBe('ONE_TO_MANY');
  });
});

function sampleTable(name: string): ErTable {
  return { name, schema: 'app', comment: '', columns: [] };
}

function sampleDiagram(): ErDiagram {
  return {
    tables: [sampleTable('users'), sampleTable('roles'), sampleTable('user_roles'), sampleTable('orders')],
    relations: [
      relation({
        name: 'fk_user',
        fromSchema: 'app',
        fromTable: 'user_roles',
        fromColumn: 'user_id',
        fromColumns: ['user_id'],
        toSchema: 'app',
        toTable: 'users',
      }),
      relation({
        name: 'fk_role',
        fromSchema: 'app',
        fromTable: 'user_roles',
        fromColumn: 'role_id',
        fromColumns: ['role_id'],
        toSchema: 'app',
        toTable: 'roles',
      }),
      relation({
        name: 'user_roles',
        cardinality: 'MANY_TO_MANY',
        fromTable: 'users',
        fromColumn: 'id',
        fromColumns: ['id'],
        toTable: 'roles',
        toColumn: 'id',
        toColumns: ['id'],
        viaSchema: 'app',
        viaTable: 'user_roles',
      }),
      relation({ name: 'fk_order', fromTable: 'orders', toTable: 'users' }),
    ],
    truncated: false,
  };
}

describe('filter tables by name', () => {
  const diagram = sampleDiagram();

  it('keeps every table when the query is blank', () => {
    expect(filterTablesByName(diagram, '  ').tables).toHaveLength(diagram.tables.length);
  });

  it('keeps a matching table and only the lines between matching tables', () => {
    const filtered = filterTablesByName(diagram, 'USER');
    expect(filtered.tables.map((table) => table.name).sort()).toEqual(['user_roles', 'users']);
    expect(filtered.relations.map((item) => item.name)).toEqual(['fk_user']);
  });

  it('keeps each comma-separated name, and only those names when exact filtering is on', () => {
    const linked: ErDiagram = {
      tables: [sampleTable('users'), sampleTable('orders'), sampleTable('user_roles')],
      relations: [
        relation({ name: 'fk_order', fromSchema: 'app', fromTable: 'orders', toSchema: 'app', toTable: 'users' }),
        relation({ name: 'fk_user', fromSchema: 'app', fromTable: 'user_roles', toSchema: 'app', toTable: 'users' }),
      ],
      truncated: false,
    };
    const listed = filterTablesByName(linked, 'users, orders');
    expect(listed.tables.map((table) => table.name).sort()).toEqual(['orders', 'users']);
    expect(listed.relations.map((item) => item.name)).toEqual(['fk_order']);

    const exact = filterTablesByName(linked, ' users , user_roles ', true);
    expect(exact.tables.map((table) => table.name).sort()).toEqual(['user_roles', 'users']);
    expect(exact.relations.map((item) => item.name)).toEqual(['fk_user']);
    expect(filterTablesByName(linked, 'user', true).tables).toHaveLength(0);
  });

  it('matches an external table by schema and name', () => {
    const external: ErDiagram = {
      tables: [
        { name: 'orders', comment: '', columns: [] },
        { name: 'emp', schema: 'SYSDBA', external: true, comment: '', columns: [] },
      ],
      relations: [relation({ fromTable: 'orders', toTable: 'emp', toSchema: 'SYSDBA' })],
      truncated: false,
    };
    const filtered = filterTablesByName(external, 'sysdba.emp');
    expect(filtered.tables.map((table) => table.name)).toEqual(['emp']);
    expect(filtered.relations).toHaveLength(0);
  });

  it('does not keep every table when a letter only appears in the schema name', () => {
    const sakila: ErDiagram = {
      tables: ['actor', 'address', 'film', 'city'].map((name) => ({ name, schema: 'sakila', comment: '', columns: [] })),
      relations: [],
      truncated: false,
    };
    expect(filterTablesByName(sakila, 'a').tables.map((table) => table.name).sort()).toEqual(['actor', 'address']);
    expect(filterTablesByName(sakila, 'c').tables.map((table) => table.name).sort()).toEqual(['actor', 'city']);
    expect(filterTablesByName(sakila, 'sakila').tables).toHaveLength(4);
  });
});

describe('fit diagram view', () => {
  const card = (x: number, y: number) => ({ x, y, width: 260, height: 200 });

  it('shrinks a diagram that is larger than the viewport and keeps a margin', () => {
    const fitted = fitDiagramView([card(24, 24), card(400, 400)], 500, 400);
    expect(fitted).not.toBeNull();
    expect(fitted!.scale).toBeLessThan(1);
    const minX = 24;
    const minY = 24;
    const maxX = 660;
    const maxY = 600;
    expect(fitted!.x + minX * fitted!.scale).toBeGreaterThanOrEqual(32 - 0.01);
    expect(fitted!.y + minY * fitted!.scale).toBeGreaterThanOrEqual(32 - 0.01);
    expect(fitted!.x + maxX * fitted!.scale).toBeLessThanOrEqual(500 - 32 + 0.01);
    expect(fitted!.y + maxY * fitted!.scale).toBeLessThanOrEqual(400 - 32 + 0.01);
  });

  it('centers a diagram that already fits without enlarging it', () => {
    const fitted = fitDiagramView([card(24, 24)], 1200, 800);
    expect(fitted!.scale).toBe(1);
    expect(fitted!.x + 24 + 130).toBeCloseTo(600);
    expect(fitted!.y + 24 + 100).toBeCloseTo(400);
  });

  it('returns null when there is nothing to fit', () => {
    expect(fitDiagramView([], 800, 600)).toBeNull();
    expect(fitDiagramView([card(0, 0)], 0, 600)).toBeNull();
  });
});

describe('zoom diagram view', () => {
  it('keeps the diagram point under the pointer when zooming in at the bottom right', () => {
    const view = { x: 40, y: 30, scale: 0.5 };
    const pointerX = 720;
    const pointerY = 480;
    const canvasX = (pointerX - view.x) / view.scale;
    const canvasY = (pointerY - view.y) / view.scale;
    const zoomed = zoomDiagramView(view, pointerX, pointerY, -100);
    expect(zoomed.scale).toBeGreaterThan(view.scale);
    expect(zoomed.x + canvasX * zoomed.scale).toBeCloseTo(pointerX);
    expect(zoomed.y + canvasY * zoomed.scale).toBeCloseTo(pointerY);
  });

  it('keeps the same point under the pointer when zooming out', () => {
    const view = { x: -80, y: -40, scale: 1.2 };
    const pointerX = 500;
    const pointerY = 360;
    const canvasX = (pointerX - view.x) / view.scale;
    const canvasY = (pointerY - view.y) / view.scale;
    const zoomed = zoomDiagramView(view, pointerX, pointerY, 100);
    expect(zoomed.scale).toBeLessThan(view.scale);
    expect(zoomed.x + canvasX * zoomed.scale).toBeCloseTo(pointerX);
    expect(zoomed.y + canvasY * zoomed.scale).toBeCloseTo(pointerY);
  });
});
