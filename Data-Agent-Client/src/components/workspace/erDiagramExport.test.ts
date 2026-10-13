import { describe, expect, it } from 'vitest';
import { erDiagramFilename, parseDiagramPath } from './erDiagramExport';

describe('ER diagram export', () => {
  it('keeps a readable png filename', () => {
    expect(erDiagramFilename('sakila')).toBe('sakila.png');
    expect(erDiagramFilename('ER/DEMO:1')).toBe('ER-DEMO-1.png');
    expect(erDiagramFilename('   ')).toBe('er-diagram.png');
  });

  it('reads the line points that the diagram draws', () => {
    expect(parseDiagramPath('M 10 20 L 30 20 L 30 80')).toEqual([
      { x: 10, y: 20 },
      { x: 30, y: 20 },
      { x: 30, y: 80 },
    ]);
  });
});
