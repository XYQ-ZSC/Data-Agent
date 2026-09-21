import { useCallback } from 'react';
import type React from 'react';
import { useTranslation } from 'react-i18next';
import { I18N_KEYS } from '../constants/i18nKeys';
import { useToast } from './useToast';
import { tableService } from '../services/table.service';
import { renameNodeById } from '../utils/treeOperations';
import type { ExplorerNode } from '../types/explorer';

interface RenameState {
  node: ExplorerNode | null;
  isOpen: boolean;
  isPending: boolean;
}

interface RenameActionsProps {
  setRenameState: React.Dispatch<React.SetStateAction<RenameState>>;
  renameState: RenameState;
  setTreeDataState: (cb: (prev: ExplorerNode[]) => ExplorerNode[]) => void;
}

export function useRenameActions({
  setRenameState,
  renameState,
  setTreeDataState,
}: RenameActionsProps) {
  const { t } = useTranslation();
  const toast = useToast();

  const handleRename = useCallback((node: ExplorerNode) => {
    if (!node.connectionId) return;
    setRenameState({ node, isOpen: true, isPending: false });
  }, [setRenameState]);

  const confirmRename = useCallback(async (newName: string) => {
    const { node } = renameState;
    const trimmedName = newName.trim();
    if (!node || !trimmedName || trimmedName === node.name) {
      setRenameState({ node: null, isOpen: false, isPending: false });
      return;
    }

    setRenameState((prev) => ({ ...prev, isPending: true }));

    try {
      await tableService.renameTable(
        node.connectionId!,
        node.name,
        trimmedName,
        node.catalog,
        node.schema,
      );

      setTreeDataState((prev: ExplorerNode[]) => renameNodeById(prev, node.id, trimmedName));
      toast.success(t(I18N_KEYS.EXPLORER.RENAME_TABLE_SUCCESS));
    } catch (error) {
      console.error('Failed to rename table:', error);
      toast.error((error as Error).message || t(I18N_KEYS.EXPLORER.RENAME_TABLE_FAILED));
    } finally {
      setRenameState({ node: null, isOpen: false, isPending: false });
    }
  }, [renameState, setRenameState, setTreeDataState, t, toast]);

  return { handleRename, confirmRename };
}
