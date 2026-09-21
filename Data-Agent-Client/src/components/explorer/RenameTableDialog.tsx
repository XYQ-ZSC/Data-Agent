import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../ui/Dialog';
import { Button } from '../ui/Button';
import { Input } from '../ui/Input';
import { I18N_KEYS } from '../../constants/i18nKeys';

interface RenameTableDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  tableName: string;
  onConfirm: (newName: string) => void;
  isPending: boolean;
}

export function RenameTableDialog({
  open,
  onOpenChange,
  tableName,
  onConfirm,
  isPending,
}: RenameTableDialogProps) {
  const { t } = useTranslation();
  const [newName, setNewName] = useState(tableName);

  // Reset the input whenever the dialog is opened for another table.
  useEffect(() => {
    if (open) {
      setNewName(tableName);
    }
  }, [open, tableName]);

  const trimmedName = newName.trim();
  const canConfirm = !isPending && trimmedName !== '' && trimmedName !== tableName;

  const handleConfirm = () => {
    if (!canConfirm) return;
    onConfirm(trimmedName);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[400px]">
        <DialogHeader>
          <DialogTitle>{t(I18N_KEYS.EXPLORER.RENAME_TABLE)}</DialogTitle>
          <DialogDescription>
            {t(I18N_KEYS.EXPLORER.RENAME_TABLE_PROMPT, { name: tableName })}
          </DialogDescription>
        </DialogHeader>
        <Input
          autoFocus
          value={newName}
          disabled={isPending}
          onChange={(e) => setNewName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              handleConfirm();
            }
          }}
        />
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isPending}>
            {t(I18N_KEYS.CONNECTIONS.CANCEL)}
          </Button>
          <Button disabled={!canConfirm} onClick={handleConfirm}>
            {isPending ? t(I18N_KEYS.COMMON.LOADING) + '...' : t(I18N_KEYS.EXPLORER.RENAME_TABLE)}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
