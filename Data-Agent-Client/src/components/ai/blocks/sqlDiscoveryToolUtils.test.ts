import { describe, expect, it } from 'vitest';
import { getToolDisplayName } from './sqlDiscoveryToolUtils';

describe('sqlDiscoveryToolUtils', () => {
  it('maps background SQL tools to readable fallback labels', () => {
    expect(getToolDisplayName('getConnections')).toBe('Get Connections');
    expect(getToolDisplayName('getDatabases')).toBe('Get Databases');
    expect(getToolDisplayName('getSchemas')).toBe('Get Schemas');
    expect(getToolDisplayName('searchObjects')).toBe('Search Objects');
    expect(getToolDisplayName('getObjectDetail')).toBe('Get Object Detail');
    expect(getToolDisplayName('unknownTool')).toBe('unknownTool');
  });
});
