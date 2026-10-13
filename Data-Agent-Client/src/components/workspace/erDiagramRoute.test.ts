import { describe, expect, it } from 'vitest';
import { routeAroundNodes, type DiagramRect } from './erDiagramRoute';

function hits(points: Array<{ x: number; y: number }>, rect: DiagramRect) {
  for (let index = 1; index < points.length; index += 1) {
    const a = points[index - 1];
    const b = points[index];
    const horizontal = Math.abs(a.y - b.y) < 0.01;
    const vertical = Math.abs(a.x - b.x) < 0.01;
    expect(horizontal || vertical).toBe(true);
    if (horizontal) {
      const minX = Math.min(a.x, b.x);
      const maxX = Math.max(a.x, b.x);
      const crosses = maxX > rect.x && minX < rect.x + rect.width
        && a.y > rect.y && a.y < rect.y + rect.height;
      if (crosses) return true;
    }
    if (vertical) {
      const minY = Math.min(a.y, b.y);
      const maxY = Math.max(a.y, b.y);
      const crosses = maxY > rect.y && minY < rect.y + rect.height
        && a.x > rect.x && a.x < rect.x + rect.width;
      if (crosses) return true;
    }
  }
  return false;
}

describe('orthogonal ER routes', () => {
  it('walks around a table that sits between the two ends', () => {
    const blocker: DiagramRect = { id: 'mid', x: 140, y: 0, width: 80, height: 80 };
    const points = routeAroundNodes(
      { x: 100, y: 40 },
      { x: 280, y: 40 },
      'right',
      'left',
      [
        { id: 'from', x: 0, y: 0, width: 100, height: 80 },
        blocker,
        { id: 'to', x: 280, y: 0, width: 100, height: 80 },
      ],
      'from',
      'to',
    );
    expect(hits(points, blocker)).toBe(false);
    expect(points[0]).toEqual({ x: 100, y: 40 });
    expect(points[points.length - 1]).toEqual({ x: 280, y: 40 });
  });
});
