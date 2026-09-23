/** Interpret table editor input without collapsing DM empty strings into SQL NULL. */
export function normalizeInsertInput(rawValue: string | undefined, isDm: boolean): string | null {
  const trimmed = (rawValue ?? '').trim();
  if (trimmed.toUpperCase() === 'NULL' || ((!isDm || rawValue === undefined) && trimmed === '')) {
    return null;
  }
  return isDm ? rawValue ?? '' : trimmed;
}

export function normalizeEditedCellInput(rawValue: unknown, oldValue: unknown, isDm: boolean): unknown {
  if (rawValue == null) return null;
  const text = String(rawValue);
  if (text.toUpperCase() === 'NULL' || (!isDm && text === '')) return null;
  if (typeof oldValue === 'number' && text !== '') {
    const parsed = Number(text);
    return Number.isFinite(parsed) ? parsed : text;
  }
  return text;
}
