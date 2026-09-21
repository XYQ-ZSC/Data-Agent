import { useTranslation } from 'react-i18next';
import { Archive } from 'lucide-react';
import { I18N_KEYS } from '../../../constants/i18nKeys';

export function CompactSummaryBubble() {
  const { t } = useTranslation();

  return (
    <div className="flex items-center space-x-2 opacity-70 px-3 py-2">
      <Archive className="w-3 h-3 shrink-0" />
      <span className="text-xs theme-text-secondary">
        {t(I18N_KEYS.AI.COMPACT.SUMMARY_LABEL)}
      </span>
    </div>
  );
}
