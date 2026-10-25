import { useCallback, useEffect, useRef, useState } from 'react';
import { MiniMap, useOnViewportChange, useStore, useStoreApi } from '@xyflow/react';
import { useTranslation } from 'react-i18next';
import { I18N_KEYS } from '../../constants/i18nKeys';
import {
  CARD_WIDTH,
  cardHeight,
  ER_MINIMAP_HEIGHT,
  ER_MINIMAP_WIDTH,
  ER_OVERVIEW_GAP,
  ER_PANEL_MARGIN,
  ER_ZOOM_BAR_HEIGHT,
  minimapNeeded,
} from './erDiagramLayout';
import type { ErTableNodeType } from './ErDiagramNode';

const SETTLE_MS = 160;
const LOCAL_NODE = 'var(--accent-blue)';
const EXTERNAL_NODE = '#fb7185';

function rectOf(node: ErTableNodeType) {
  const table = node.data?.table;
  return {
    x: node.position.x,
    y: node.position.y,
    width: node.measured?.width || node.width || CARD_WIDTH,
    height: node.measured?.height || node.height || (table ? cardHeight(table.columns.length, !!node.data.expanded) : 0),
  };
}

function nodeColor(node: ErTableNodeType) {
  return node.data.table.external ? EXTERNAL_NODE : LOCAL_NODE;
}

export function ErDiagramMiniMap({ ready, disabled = false }: { ready: boolean; disabled?: boolean }) {
  const { t } = useTranslation();
  const store = useStoreApi();
  const nodes = useStore((state) => state.nodes) as ErTableNodeType[];
  const paneWidth = useStore((state) => state.width);
  const paneHeight = useStore((state) => state.height);
  const [visible, setVisible] = useState(false);
  const visibleRef = useRef(false);
  const readyRef = useRef(ready);
  const interacting = useRef(false);
  const settleTimer = useRef(0);
  readyRef.current = ready;

  const publish = useCallback(() => {
    if (!readyRef.current) return;
    const state = store.getState();
    const [x, y, zoom] = state.transform;
    const next = minimapNeeded(
      visibleRef.current,
      (state.nodes as ErTableNodeType[]).map(rectOf),
      { x, y, zoom },
      state.width,
      state.height,
    );
    if (next === visibleRef.current) return;
    visibleRef.current = next;
    setVisible(next);
  }, [store]);

  const onViewportStart = useCallback(() => {
    interacting.current = true;
    window.clearTimeout(settleTimer.current);
  }, []);

  const onViewportEnd = useCallback(() => {
    window.clearTimeout(settleTimer.current);
    settleTimer.current = window.setTimeout(() => {
      interacting.current = false;
      publish();
    }, SETTLE_MS);
  }, [publish]);

  useOnViewportChange({ onStart: onViewportStart, onEnd: onViewportEnd });

  useEffect(() => () => window.clearTimeout(settleTimer.current), []);

  useEffect(() => {
    if (!ready) {
      window.clearTimeout(settleTimer.current);
      interacting.current = false;
      visibleRef.current = false;
      setVisible(false);
      return;
    }
    if (interacting.current) return;
    publish();
  }, [nodes, paneHeight, paneWidth, publish, ready]);

  if (!visible) return null;

  return (
    <MiniMap<ErTableNodeType>
      position="bottom-right"
      pannable={!disabled}
      zoomable={false}
      ariaLabel={t(I18N_KEYS.EXPLORER.ER_DIAGRAM_MINIMAP)}
      nodeColor={nodeColor}
      nodeStrokeWidth={0}
      nodeBorderRadius={2}
      bgColor="var(--bg-popup)"
      maskColor="color-mix(in srgb, var(--bg-main) 58%, transparent)"
      maskStrokeColor="var(--text-primary)"
      maskStrokeWidth={1.5}
      className={`er-diagram-minimap rounded-md border theme-border shadow-sm${disabled ? ' pointer-events-none opacity-40' : ''}`}
      style={{
        width: ER_MINIMAP_WIDTH,
        height: ER_MINIMAP_HEIGHT,
        marginBottom: ER_PANEL_MARGIN + ER_ZOOM_BAR_HEIGHT + ER_OVERVIEW_GAP,
      }}
    />
  );
}
