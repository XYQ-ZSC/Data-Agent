/**
 * Format tool parameters for display.
 * Attempts to parse as JSON and pretty-print, falls back to raw string.
 */
export function formatParameters(parametersData: string): string {
  if (!parametersData?.trim()) return parametersData;

  try {
    let parsed: unknown = JSON.parse(parametersData);
    if (typeof parsed === 'string') {
      parsed = JSON.parse(parsed) as unknown;
    }
    return JSON.stringify(removeUiOnlyDescription(parsed), null, 2);
  } catch {
    return parametersData;
  }
}

function removeUiOnlyDescription(value: unknown): unknown {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return value;
  }

  const { description: _description, ...rest } = value as Record<string, unknown>;
  return rest;
}
