import { describe, expect, it } from 'vitest';
import { erDiagramFilename, paintableStyleValue, scrubXmlText } from './erDiagramExport';

describe('ER diagram export', () => {
  it('keeps a readable png filename', () => {
    expect(erDiagramFilename('sakila')).toBe('sakila.png');
    expect(erDiagramFilename('ER/DEMO:1')).toBe('ER-DEMO-1.png');
    expect(erDiagramFilename('   ')).toBe('er-diagram.png');
  });

  it('drops characters and color functions that make an SVG export fail to decode', () => {
    expect(scrubXmlText('orders\u001fusers')).toBe('ordersusers');
    expect(paintableStyleValue('#f4f7fb')).toBe('#f4f7fb');
    expect(paintableStyleValue('color-mix(in srgb, #fff 50%, #000)')).toBe('');
    expect(paintableStyleValue('var(--bg-main)')).toBe('');
  });
});
