import { describe, expect, it, vi } from 'vitest';
import { FolderName, ExplorerNodeType } from '../constants/explorer';
import { createCachedObjectFolders } from './connectionTreeLoader';
import { loadFolderContentsByType } from './folderContentLoaders';
import { constraintService } from '../services/constraint.service';
import { sequenceService } from '../services/sequence.service';

vi.mock('../services/constraint.service', () => ({ constraintService: { listConstraints: vi.fn() } }));
vi.mock('../services/sequence.service', () => ({ sequenceService: { listSequences: vi.fn() } }));

const context = {
  connId: '7', parentId: 'conn-7-schema-HR', catalog: '', schema: 'HR',
  t: (key: string) => key, isDm: true,
};

describe('DM explorer objects', () => {
  it('shows a lazy sequence folder and loads its objects in the selected schema', async () => {
    const folders = createCachedObjectFolders(context, [], []);
    expect(folders.find((folder) => folder.folderName === FolderName.SEQUENCES)).toBeDefined();
    vi.mocked(sequenceService.listSequences).mockResolvedValue([{ name: 'SEQ_1', minValue: null, maxValue: null, incrementBy: null, cycle: false, cacheSize: null }]);

    const objects = await loadFolderContentsByType({
      connId: '7', catalog: '', schema: 'HR', folderId: 'sequences',
    }, FolderName.SEQUENCES);

    expect(sequenceService.listSequences).toHaveBeenCalledWith('7', '', 'HR');
    expect(objects[0]).toMatchObject({ type: ExplorerNodeType.SEQUENCE, name: 'SEQ_1', schema: 'HR' });
  });

  it('loads constraints under their table', async () => {
    vi.mocked(constraintService.listConstraints).mockResolvedValue([{
      name: 'FK_ORDER', tableName: 'ORDERS', type: 'R', columns: ['CUSTOMER_ID'],
      referencedSchema: 'HR', referencedTable: 'CUSTOMERS', referencedColumns: ['ID'],
      checkExpression: null, enabled: true,
    }]);

    const objects = await loadFolderContentsByType({
      connId: '7', catalog: '', schema: 'HR', tableName: 'ORDERS', folderId: 'constraints',
    }, FolderName.CONSTRAINTS);

    expect(constraintService.listConstraints).toHaveBeenCalledWith('7', 'ORDERS', '', 'HR');
    expect(objects[0]).toMatchObject({ type: ExplorerNodeType.CONSTRAINT, name: 'FK_ORDER', tableName: 'ORDERS' });
  });
});
