export interface DiagramRect {
  id: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface DiagramPoint {
  x: number;
  y: number;
}

export type RouteSide = 'left' | 'right';

const STUB = 16;
const GAP = 14;

function nudge(point: DiagramPoint, side: RouteSide, distance: number): DiagramPoint {
  return { x: point.x + (side === 'right' ? distance : -distance), y: point.y };
}

function overlaps(a1: number, a2: number, b1: number, b2: number) {
  const minA = Math.min(a1, a2);
  const maxA = Math.max(a1, a2);
  return maxA > b1 + 0.5 && minA < b2 - 0.5;
}

function segmentHits(a: DiagramPoint, b: DiagramPoint, rect: DiagramRect) {
  if (Math.abs(a.y - b.y) < 0.01) {
    return overlaps(a.x, b.x, rect.x, rect.x + rect.width)
      && a.y > rect.y + 0.5
      && a.y < rect.y + rect.height - 0.5;
  }
  if (Math.abs(a.x - b.x) < 0.01) {
    return overlaps(a.y, b.y, rect.y, rect.y + rect.height)
      && a.x > rect.x + 0.5
      && a.x < rect.x + rect.width - 0.5;
  }
  return false;
}

function pathHits(points: DiagramPoint[], rects: DiagramRect[]) {
  for (let index = 1; index < points.length; index += 1) {
    if (rects.some((rect) => segmentHits(points[index - 1], points[index], rect))) return true;
  }
  return false;
}

function simplify(points: DiagramPoint[]) {
  const result: DiagramPoint[] = [];
  points.forEach((point) => {
    const last = result[result.length - 1];
    if (last && Math.abs(last.x - point.x) < 0.01 && Math.abs(last.y - point.y) < 0.01) return;
    if (result.length >= 2) {
      const previous = result[result.length - 2];
      const vertical = Math.abs(previous.x - last.x) < 0.01 && Math.abs(last.x - point.x) < 0.01;
      const horizontal = Math.abs(previous.y - last.y) < 0.01 && Math.abs(last.y - point.y) < 0.01;
      if (vertical || horizontal) {
        result[result.length - 1] = point;
        return;
      }
    }
    result.push(point);
  });
  return result;
}

/** Orthogonal path from one column edge to another, kept outside every other table. */
export function routeAroundNodes(
  from: DiagramPoint,
  to: DiagramPoint,
  fromSide: RouteSide,
  toSide: RouteSide,
  obstacles: DiagramRect[],
  sourceId: string,
  targetId: string,
  lane = 0,
): DiagramPoint[] {
  const blocks = obstacles
    .filter((rect) => rect.id !== sourceId && rect.id !== targetId)
    .map((rect) => ({
      ...rect,
      x: rect.x - GAP,
      y: rect.y - GAP,
      width: rect.width + GAP * 2,
      height: rect.height + GAP * 2,
    }));
  const start = nudge(from, fromSide, STUB);
  const end = nudge(to, toSide, STUB);
  const laneShift = lane * 8;
  const elbow = simplify([
    from,
    start,
    { x: end.x, y: start.y + laneShift },
    { x: end.x, y: end.y },
    end,
    to,
  ]);
  const otherElbow = simplify([
    from,
    start,
    { x: start.x, y: end.y + laneShift },
    end,
    to,
  ]);
  if (!pathHits(elbow, blocks)) return elbow;
  if (!pathHits(otherElbow, blocks)) return otherElbow;
  if (blocks.length === 0) return elbow;

  const span = blocks.filter((rect) => (
    overlaps(start.x, end.x, rect.x, rect.x + rect.width)
    && overlaps(Math.min(start.y, end.y) - 40, Math.max(start.y, end.y) + 40, rect.y, rect.y + rect.height)
  ));
  const relevant = span.length > 0 ? span : blocks;
  const top = Math.min(...relevant.map((rect) => rect.y)) - GAP;
  const bottom = Math.max(...relevant.map((rect) => rect.y + rect.height)) + GAP;
  const above = simplify([
    from,
    start,
    { x: start.x, y: top + laneShift },
    { x: end.x, y: top + laneShift },
    end,
    to,
  ]);
  const below = simplify([
    from,
    start,
    { x: start.x, y: bottom + laneShift },
    { x: end.x, y: bottom + laneShift },
    end,
    to,
  ]);
  const aboveHit = pathHits(above, blocks);
  const belowHit = pathHits(below, blocks);
  if (!aboveHit && !belowHit) {
    return Math.abs(top - start.y) <= Math.abs(bottom - start.y) ? above : below;
  }
  if (!aboveHit) return above;
  if (!belowHit) return below;
  const outerTop = Math.min(...blocks.map((rect) => rect.y)) - GAP;
  const outerBottom = Math.max(...blocks.map((rect) => rect.y + rect.height)) + GAP;
  return Math.abs(outerTop - start.y) <= Math.abs(outerBottom - start.y)
    ? simplify([from, start, { x: start.x, y: outerTop }, { x: end.x, y: outerTop }, end, to])
    : simplify([from, start, { x: start.x, y: outerBottom }, { x: end.x, y: outerBottom }, end, to]);
}
