import { getViewportForBounds, type Rect } from '@xyflow/react';
import { toCanvas } from 'html-to-image';

export function erDiagramFilename(name: string) {
  const safe = name.trim().replace(/[\\/:*?"<>|]+/g, '-').replace(/\s+/g, ' ').trim() || 'er-diagram';
  return `${safe}.png`;
}

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Give the browser time to start the download before releasing its URL.
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
const PADDING = 48;

export function exportImageSize(bounds: Rect) {
  const width = Math.ceil(bounds.width + PADDING * 2);
  const height = Math.ceil(bounds.height + PADDING * 2);
  // Limit both the longest side and total memory without cropping the diagram.
  const scale = Math.min(1, 8192 / width, 8192 / height, Math.sqrt(16_000_000 / (width * height)));
  return { width: Math.max(1, Math.floor(width * scale)), height: Math.max(1, Math.floor(height * scale)) };
}

export function includeBounds(bounds: Rect, other: Rect): Rect {
  const x = Math.min(bounds.x, other.x);
  const y = Math.min(bounds.y, other.y);
  return {
    x,
    y,
    width: Math.max(bounds.x + bounds.width, other.x + other.width) - x,
    height: Math.max(bounds.y + bounds.height, other.y + other.height) - y,
  };
}

export function hasImageContent(pixels: Uint8ClampedArray) {
  // The first pixel is in the padded background. A uniform bitmap is a failed capture.
  for (let index = 4; index < pixels.length; index += 4) {
    if (pixels[index] !== pixels[0] || pixels[index + 1] !== pixels[1]
      || pixels[index + 2] !== pixels[2] || pixels[index + 3] !== pixels[3]) return true;
  }
  return false;
}

/** Exports the currently displayed fields and relations, independent of pan/zoom. */
export async function renderErDiagramPng(viewport: HTMLElement, nodeBounds: Rect, background: string): Promise<Blob> {
  await document.fonts.ready;
  await new Promise(requestAnimationFrame);
  await new Promise(requestAnimationFrame);
  let bounds = nodeBounds;
  viewport.querySelectorAll<SVGPathElement>('.react-flow__edge-path').forEach((path) => {
    // Routes may travel outside the node bounds; keep the entire line in the image.
    bounds = includeBounds(bounds, path.getBBox());
  });
  const { width, height } = exportImageSize(bounds);
  const fitted = getViewportForBounds(bounds, width, height, 0, 1, `${PADDING}px`);
  const canvas = await toCanvas(viewport, {
    backgroundColor: background,
    width,
    height,
    pixelRatio: 1,
    filter: (node) => !(node instanceof Element && node.hasAttribute('data-er-export-hidden')),
    style: {
      width: `${width}px`,
      height: `${height}px`,
      transform: `translate(${fitted.x}px, ${fitted.y}px) scale(${fitted.zoom})`,
    },
  });
  const context = canvas.getContext('2d');
  if (!context || !hasImageContent(context.getImageData(0, 0, canvas.width, canvas.height).data)) {
    throw new Error('ER diagram capture is blank');
  }
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => blob ? resolve(blob) : reject(new Error('Unable to encode ER diagram')), 'image/png');
  });
}
