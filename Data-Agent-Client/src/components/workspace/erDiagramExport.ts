import type { EndMark } from './erDiagramRelations';

const HEADER_HEIGHT = 34;
const ROW_HEIGHT = 22;
const MAX_IMAGE_SIDE = 8192;

export interface ErExportColumn {
  name: string;
  typeName: string;
  comment: string;
  primaryKey: boolean;
  linked: boolean;
  highlighted: boolean;
}

export interface ErExportBadge {
  text: string;
  color: string;
}

export interface ErExportCard {
  x: number;
  y: number;
  width: number;
  height: number;
  title: string;
  external: boolean;
  badges: ErExportBadge[];
  columns: ErExportColumn[];
  scrollTop: number;
}

export interface ErExportLine {
  d: string;
  color: string;
  dashed: boolean;
  fromMark: EndMark;
  toMark: EndMark;
}

export interface ErExportScene {
  width: number;
  height: number;
  background: string;
  grid: string;
  cardBackground: string;
  border: string;
  textPrimary: string;
  textSecondary: string;
  typeColor: string;
  keyColor: string;
  highlight: string;
  cards: ErExportCard[];
  lines: ErExportLine[];
}

export function erDiagramFilename(name: string) {
  const safe = name.trim().replace(/[\\/:*?"<>|]+/g, '-').replace(/\s+/g, ' ').trim() || 'er-diagram';
  return `${safe}.png`;
}

export function parseDiagramPath(d: string): Array<{ x: number; y: number }> {
  const points: Array<{ x: number; y: number }> = [];
  const tokens = d.trim().split(/[\s,]+/).filter(Boolean);
  let index = 0;
  while (index < tokens.length) {
    const command = tokens[index];
    if ((command === 'M' || command === 'L') && index + 2 < tokens.length) {
      const x = Number(tokens[index + 1]);
      const y = Number(tokens[index + 2]);
      if (Number.isFinite(x) && Number.isFinite(y)) points.push({ x, y });
      index += 3;
      continue;
    }
    index += 1;
  }
  return points;
}

function roundRect(ctx: CanvasRenderingContext2D, x: number, y: number, width: number, height: number, radius: number) {
  const r = Math.min(radius, width / 2, height / 2);
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + width, y, x + width, y + height, r);
  ctx.arcTo(x + width, y + height, x, y + height, r);
  ctx.arcTo(x, y + height, x, y, r);
  ctx.arcTo(x, y, x + width, y, r);
  ctx.closePath();
}

function fillEllipsis(ctx: CanvasRenderingContext2D, text: string, x: number, y: number, maxWidth: number) {
  if (!text || maxWidth <= 0) return;
  if (ctx.measureText(text).width <= maxWidth) {
    ctx.fillText(text, x, y);
    return;
  }
  let end = text.length;
  while (end > 0 && ctx.measureText(`${text.slice(0, end)}…`).width > maxWidth) end -= 1;
  if (end > 0) ctx.fillText(`${text.slice(0, end)}…`, x, y);
}

function drawMarker(ctx: CanvasRenderingContext2D, x: number, y: number, angle: number, mark: EndMark, color: string) {
  ctx.save();
  ctx.translate(x, y);
  ctx.rotate(angle);
  ctx.translate(-13, -7);
  ctx.strokeStyle = color;
  ctx.lineWidth = 1.6;
  ctx.lineCap = 'round';
  ctx.beginPath();
  if (mark === 'one') {
    ctx.moveTo(11, 1);
    ctx.lineTo(11, 13);
  } else {
    ctx.moveTo(1, 7);
    ctx.lineTo(13, 1);
    ctx.moveTo(1, 7);
    ctx.lineTo(13, 7);
    ctx.moveTo(1, 7);
    ctx.lineTo(13, 13);
  }
  ctx.stroke();
  ctx.restore();
}

function drawLine(ctx: CanvasRenderingContext2D, line: ErExportLine) {
  const points = parseDiagramPath(line.d);
  if (points.length < 2) return;
  ctx.save();
  ctx.strokeStyle = line.color;
  ctx.lineWidth = 1.6;
  ctx.lineJoin = 'round';
  ctx.lineCap = 'round';
  ctx.setLineDash(line.dashed ? [5, 4] : []);
  ctx.beginPath();
  points.forEach((point, index) => {
    if (index === 0) ctx.moveTo(point.x, point.y);
    else ctx.lineTo(point.x, point.y);
  });
  ctx.stroke();
  ctx.restore();
  const start = points[0];
  const next = points[1];
  const end = points[points.length - 1];
  const previous = points[points.length - 2];
  drawMarker(ctx, start.x, start.y, Math.atan2(start.y - next.y, start.x - next.x), line.fromMark, line.color);
  drawMarker(ctx, end.x, end.y, Math.atan2(end.y - previous.y, end.x - previous.x), line.toMark, line.color);
}

function drawKey(ctx: CanvasRenderingContext2D, x: number, y: number, color: string) {
  ctx.save();
  ctx.translate(x, y);
  ctx.strokeStyle = color;
  ctx.lineWidth = 1.2;
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.arc(3.2, 5.5, 2.1, 0, Math.PI * 2);
  ctx.moveTo(5.1, 5.5);
  ctx.lineTo(11, 5.5);
  ctx.moveTo(8.2, 5.5);
  ctx.lineTo(8.2, 7.4);
  ctx.moveTo(10.2, 5.5);
  ctx.lineTo(10.2, 7.4);
  ctx.stroke();
  ctx.restore();
}

function drawCard(ctx: CanvasRenderingContext2D, card: ErExportCard, scene: ErExportScene) {
  ctx.save();
  ctx.shadowColor = 'rgba(15, 23, 42, 0.12)';
  ctx.shadowBlur = 8;
  ctx.shadowOffsetY = 1;
  roundRect(ctx, card.x, card.y, card.width, card.height, 6);
  ctx.fillStyle = scene.cardBackground;
  ctx.fill();
  ctx.restore();

  ctx.save();
  ctx.strokeStyle = card.external ? '#fb7185' : scene.border;
  ctx.lineWidth = 1;
  ctx.setLineDash(card.external ? [4, 3] : []);
  roundRect(ctx, card.x + 0.5, card.y + 0.5, card.width - 1, card.height - 1, 6);
  ctx.stroke();
  ctx.restore();

  ctx.save();
  ctx.beginPath();
  ctx.rect(card.x + 1, card.y + 1, card.width - 2, HEADER_HEIGHT);
  ctx.clip();
  ctx.fillStyle = scene.cardBackground;
  ctx.fillRect(card.x, card.y, card.width, HEADER_HEIGHT);
  ctx.strokeStyle = scene.border;
  ctx.beginPath();
  ctx.moveTo(card.x, card.y + HEADER_HEIGHT);
  ctx.lineTo(card.x + card.width, card.y + HEADER_HEIGHT);
  ctx.stroke();

  ctx.font = '600 12px sans-serif';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = scene.textPrimary;
  const badgeWidth = card.badges.reduce((width, badge) => {
    ctx.font = '11px sans-serif';
    return width + ctx.measureText(badge.text).width + 8;
  }, 0);
  ctx.font = '600 12px sans-serif';
  const titleX = card.x + 8;
  const titleWidth = Math.max(24, card.width - 16 - badgeWidth);
  fillEllipsis(ctx, card.title, titleX, card.y + HEADER_HEIGHT / 2, titleWidth);
  let badgeX = card.x + card.width - 8;
  ctx.font = '11px sans-serif';
  ctx.textAlign = 'right';
  card.badges.slice().reverse().forEach((badge) => {
    ctx.fillStyle = badge.color;
    ctx.fillText(badge.text, badgeX, card.y + HEADER_HEIGHT / 2);
    badgeX -= ctx.measureText(badge.text).width + 8;
  });
  ctx.textAlign = 'left';
  ctx.restore();

  const bodyTop = card.y + HEADER_HEIGHT;
  const bodyBottom = card.y + card.height - 1;
  ctx.save();
  ctx.beginPath();
  ctx.rect(card.x + 1, bodyTop, card.width - 2, Math.max(0, bodyBottom - bodyTop));
  ctx.clip();
  const innerWidth = card.width - 2 - 16;
  const nameWidth = innerWidth * 0.38;
  const typeWidth = innerWidth * 0.28;
  const commentX = card.x + 8 + 12 + 4 + 8 + 4 + nameWidth + 4 + typeWidth + 4;
  const commentWidth = Math.max(0, card.x + card.width - 8 - commentX);
  card.columns.forEach((column, index) => {
    const rowY = bodyTop - card.scrollTop + index * ROW_HEIGHT;
    if (rowY + ROW_HEIGHT <= bodyTop || rowY >= bodyBottom) return;
    if (column.highlighted) {
      ctx.fillStyle = scene.highlight;
      ctx.fillRect(card.x + 1, rowY, card.width - 2, ROW_HEIGHT);
    }
    const midY = rowY + ROW_HEIGHT / 2;
    if (column.primaryKey) drawKey(ctx, card.x + 8, rowY + 5, scene.keyColor);
    if (column.linked) {
      ctx.beginPath();
      ctx.fillStyle = '#e11d48';
      ctx.arc(card.x + 8 + 12 + 4 + 4, midY, 3, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.textBaseline = 'middle';
    ctx.font = '11px sans-serif';
    ctx.fillStyle = scene.textPrimary;
    fillEllipsis(ctx, column.name, card.x + 8 + 12 + 4 + 8 + 4, midY, nameWidth);
    ctx.fillStyle = scene.typeColor;
    fillEllipsis(ctx, column.typeName, card.x + 8 + 12 + 4 + 8 + 4 + nameWidth + 4, midY, typeWidth);
    ctx.fillStyle = scene.textSecondary;
    fillEllipsis(ctx, column.comment, commentX, midY, commentWidth);
  });
  ctx.restore();
}

function paintScene(ctx: CanvasRenderingContext2D, scene: ErExportScene) {
  ctx.fillStyle = scene.background;
  ctx.fillRect(0, 0, scene.width, scene.height);
  ctx.fillStyle = scene.grid;
  for (let x = 9; x < scene.width; x += 18) {
    for (let y = 9; y < scene.height; y += 18) {
      ctx.fillRect(x, y, 1, 1);
    }
  }
  scene.lines.forEach((line) => drawLine(ctx, line));
  scene.cards.forEach((card) => drawCard(ctx, card, scene));
}

export function renderErDiagramPng(scene: ErExportScene): Promise<Blob> {
  const longest = Math.max(scene.width, scene.height, 1);
  const ratio = Math.min(2, MAX_IMAGE_SIDE / longest);
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(scene.width * ratio));
  canvas.height = Math.max(1, Math.round(scene.height * ratio));
  const ctx = canvas.getContext('2d');
  if (!ctx) return Promise.reject(new Error('canvas unavailable'));
  ctx.scale(ratio, ratio);
  paintScene(ctx, scene);
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (!blob) reject(new Error('export failed'));
      else resolve(blob);
    }, 'image/png');
  });
}

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
