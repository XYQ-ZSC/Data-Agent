import { describe, expect, it } from 'vitest';
import { getViewportForBounds } from '@xyflow/react';
import { erDiagramFilename, exportImageSize, hasImageContent, includeBounds } from './erDiagramExport';

describe('ER diagram export', () => {
  it('includes routes outside the cards, including negative coordinates', () => {
    expect(includeBounds({ x: 0, y: 0, width: 600, height: 200 }, { x: -16, y: -70, width: 632, height: 120 }))
      .toEqual({ x: -16, y: -70, width: 632, height: 270 });
  });

  it('fits a very large diagram within the image and memory limits without clipping', () => {
    const bounds = { x: -300, y: 100, width: 300_000, height: 150_000 };
    const size = exportImageSize(bounds);
    expect(size.width).toBeLessThanOrEqual(8192);
    expect(size.height).toBeLessThanOrEqual(8192);
    expect(size.width * size.height).toBeLessThanOrEqual(16_000_000);
    const view = getViewportForBounds(bounds, size.width, size.height, 0, 1, '48px');
    expect(view.x + bounds.x * view.zoom).toBeGreaterThanOrEqual(47);
    expect(view.y + bounds.y * view.zoom).toBeGreaterThanOrEqual(47);
    expect(view.x + (bounds.x + bounds.width) * view.zoom).toBeLessThanOrEqual(size.width - 47);
    expect(view.y + (bounds.y + bounds.height) * view.zoom).toBeLessThanOrEqual(size.height - 47);
  });

  it('rejects transparent or uniform background captures but accepts diagram pixels', () => {
    expect(hasImageContent(new Uint8ClampedArray(16))).toBe(false);
    expect(hasImageContent(new Uint8ClampedArray([255, 255, 255, 255, 255, 255, 255, 255]))).toBe(false);
    expect(hasImageContent(new Uint8ClampedArray([255, 255, 255, 255, 225, 29, 72, 255]))).toBe(true);
  });

  it('keeps a readable png filename', () => {
    expect(erDiagramFilename('sakila')).toBe('sakila.png');
    expect(erDiagramFilename('ER/DEMO:1')).toBe('ER-DEMO-1.png');
    expect(erDiagramFilename('   ')).toBe('er-diagram.png');
  });
});
