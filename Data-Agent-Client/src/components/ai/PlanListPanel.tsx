import { useEffect, useRef, useState, useCallback } from 'react';
import { ListTodo } from 'lucide-react';
import { cn } from '../../lib/utils';
import type { ExitPlanPayload } from './blocks/exitPlanModeTypes';
import { planTabId } from './blocks/ToolRunBlock';
import { useWorkspaceStore } from '../../store/workspaceStore';

interface PlanListPanelProps {
  open: boolean;
  onClose: () => void;
  plans: ExitPlanPayload[];
  /** Ref to the anchor element the panel should appear above */
  anchorRef: React.RefObject<HTMLElement | null>;
}

export function PlanListPanel({ open, onClose, plans, anchorRef }: PlanListPanelProps) {
  const panelRef = useRef<HTMLDivElement>(null);
  const [highlightedIndex, setHighlightedIndex] = useState(0);
  const itemRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const [pos, setPos] = useState<{ bottom: number; left: number; width: number } | null>(null);

  // Compute position from anchor
  useEffect(() => {
    if (!open || !anchorRef.current) return;
    const rect = anchorRef.current.getBoundingClientRect();
    setPos({
      bottom: window.innerHeight - rect.top + 4,
      left: rect.left,
      width: rect.width,
    });
  }, [open, anchorRef]);

  // Reset highlight to first item when panel opens
  useEffect(() => {
    if (open) {
      setHighlightedIndex(0);
    }
  }, [open]);

  // Scroll highlighted item into view
  useEffect(() => {
    if (!open) return;
    itemRefs.current[highlightedIndex]?.scrollIntoView({ block: 'nearest' });
  }, [open, highlightedIndex]);

  const handleSelect = useCallback((plan: ExitPlanPayload) => {
    const tabId = planTabId(plan.title);
    const ws = useWorkspaceStore.getState();
    const existing = ws.tabs.find((t) => t.id === tabId);
    if (existing) {
      ws.switchTab(tabId);
    } else {
      ws.openTab({
        id: tabId,
        name: plan.title,
        type: 'plan',
        metadata: { planPayload: plan },
      });
    }
    onClose();
  }, [onClose]);

  // Close on outside click (delay registration to skip the click that opened the panel)
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) {
        onClose();
      }
    };
    const raf = requestAnimationFrame(() => {
      document.addEventListener('mousedown', handler);
    });
    return () => {
      cancelAnimationFrame(raf);
      document.removeEventListener('mousedown', handler);
    };
  }, [open, onClose]);

  // Keyboard navigation: arrow keys, enter, escape
  // Delay registration to skip the Enter keypress that triggered the panel open
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      switch (e.key) {
        case 'Escape':
          e.preventDefault();
          onClose();
          break;
        case 'ArrowDown':
          e.preventDefault();
          if (plans.length > 0) {
            setHighlightedIndex((prev) => (prev + 1) % plans.length);
          }
          break;
        case 'ArrowUp':
          e.preventDefault();
          if (plans.length > 0) {
            setHighlightedIndex((prev) => (prev - 1 + plans.length) % plans.length);
          }
          break;
        case 'Enter':
          e.preventDefault();
          if (plans.length > 0 && highlightedIndex >= 0 && highlightedIndex < plans.length) {
            handleSelect(plans[highlightedIndex]);
          }
          break;
      }
    };
    const raf = requestAnimationFrame(() => {
      document.addEventListener('keydown', handler);
    });
    return () => {
      cancelAnimationFrame(raf);
      document.removeEventListener('keydown', handler);
    };
  }, [open, onClose, plans, highlightedIndex, handleSelect]);

  if (!open || !pos) return null;

  return (
    <div
      ref={panelRef}
      role="listbox"
      aria-label="Plans"
      style={{ bottom: pos.bottom, left: pos.left, width: pos.width }}
      className={cn(
        'fixed max-h-[50vh] flex flex-col',
        'theme-bg-panel theme-border border rounded-lg shadow-xl z-50',
        'animate-in fade-in slide-in-from-bottom-2 duration-200'
      )}
      onClick={(e) => e.stopPropagation()}
    >
      <div className="px-3 py-2 border-b theme-border shrink-0 flex items-center gap-2">
        <ListTodo className="w-4 h-4 text-amber-600 dark:text-amber-400" />
        <span className="text-sm font-medium theme-text-primary">Plans</span>
        <span className="text-xs theme-text-secondary ml-auto">{plans.length} plan{plans.length !== 1 ? 's' : ''}</span>
      </div>

      <div className="overflow-y-auto flex-1">
        {plans.length === 0 ? (
          <div className="px-3 py-6 text-center text-sm theme-text-secondary">
            No plans in this conversation
          </div>
        ) : (
          <div className="py-1">
            {plans.map((plan, index) => (
              <button
                key={`${plan.title}-${index}`}
                ref={(el) => { itemRefs.current[index] = el; }}
                role="option"
                aria-selected={index === highlightedIndex}
                type="button"
                onClick={() => handleSelect(plan)}
                onMouseEnter={() => setHighlightedIndex(index)}
                className={cn(
                  'w-full text-left px-3 py-2 flex items-center gap-2 transition-colors',
                  index === highlightedIndex
                    ? 'bg-amber-200 dark:bg-amber-800/70'
                    : 'hover:bg-accent/50'
                )}
              >
                <ListTodo className="w-3.5 h-3.5 text-amber-600 dark:text-amber-400 shrink-0" />
                <span className="text-[13px] theme-text-primary flex-1 truncate">{plan.title}</span>
                <span className="text-[11px] theme-text-secondary shrink-0">
                  {plan.steps.length} step{plan.steps.length !== 1 ? 's' : ''}
                </span>
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
