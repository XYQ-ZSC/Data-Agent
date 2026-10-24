import { describe, expect, it } from 'vitest';
import { erDiagramFilename } from './erDiagramExport';

describe('ER diagram export', () => {
  it('keeps a readable png filename', () => {
    expect(erDiagramFilename('sakila')).toBe('sakila.png');
    expect(erDiagramFilename('ER/DEMO:1')).toBe('ER-DEMO-1.png');
    expect(erDiagramFilename('   ')).toBe('er-diagram.png');
  });
});
