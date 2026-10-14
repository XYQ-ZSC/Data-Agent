const XML_NS = 'http://www.w3.org/1999/xhtml';
const SVG_NS = 'http://www.w3.org/2000/svg';
const ILLEGAL_XML = /[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g;

const HTML_STYLE = [
  'display', 'position', 'left', 'top', 'right', 'bottom', 'width', 'height',
  'transform', 'transform-origin', 'box-sizing', 'overflow', 'opacity', 'z-index',
  'background-color', 'color', 'border-top', 'border-right', 'border-bottom', 'border-left',
  'border-radius', 'box-shadow', 'font-size', 'font-family', 'font-weight', 'line-height',
  'padding', 'margin', 'gap', 'align-items', 'justify-content', 'flex-direction',
  'flex-shrink', 'flex-grow', 'flex-basis', 'white-space', 'text-overflow',
  'min-width', 'max-width', 'min-height', 'max-height',
];

const SVG_STYLE = [
  'overflow', 'opacity', 'color', 'fill', 'stroke', 'stroke-width', 'stroke-dasharray',
  'stroke-linecap', 'stroke-linejoin', 'marker-start', 'marker-end',
];

export function erDiagramFilename(name: string) {
  const safe = name.trim().replace(/[\\/:*?"<>|]+/g, '-').replace(/\s+/g, ' ').trim() || 'er-diagram';
  return `${safe}.png`;
}

export function scrubXmlText(value: string) {
  return value.replace(ILLEGAL_XML, '');
}

/** Drops color functions that make an SVG image fail to decode. */
export function paintableStyleValue(value: string) {
  const trimmed = value.trim();
  if (!trimmed || /color-mix\(|oklch\(|oklab\(|\bcolor\(|var\(/i.test(trimmed)) return '';
  return trimmed;
}

function inlineStyles(root: HTMLElement) {
  const view = root.ownerDocument.defaultView;
  if (!view) return;
  [root, ...root.querySelectorAll<HTMLElement | SVGElement>('*')].forEach((element) => {
    const computed = view.getComputedStyle(element);
    const names = element instanceof SVGSVGElement
      ? [...SVG_STYLE, 'width', 'height']
      : element instanceof SVGElement
        ? SVG_STYLE
        : HTML_STYLE;
    names.forEach((name) => {
      const value = paintableStyleValue(computed.getPropertyValue(name));
      if (!value) return;
      element.style.setProperty(name, value);
    });
  });
}

function scrubElement(element: Element) {
  [...element.attributes].forEach((attribute) => {
    const value = scrubXmlText(attribute.value);
    if (value !== attribute.value) element.setAttribute(attribute.name, value);
  });
}

function scrubTree(root: HTMLElement) {
  scrubElement(root);
  const walker = root.ownerDocument.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
  const nodes: Node[] = [];
  while (walker.nextNode()) nodes.push(walker.currentNode);
  nodes.forEach((node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      node.textContent = scrubXmlText(node.textContent ?? '');
      return;
    }
    if (node instanceof Element) scrubElement(node);
  });
}

function loadImage(url: string) {
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error('ER diagram image failed to decode'));
    image.src = url;
  });
}

export interface DiagramExportFrame {
  viewport: HTMLElement;
  width: number;
  height: number;
  translateX: number;
  translateY: number;
  zoom: number;
  background: string;
  flowExtent: number;
}

/** Clones the on-screen diagram and paints that DOM into a PNG. Edges stay in the outer SVG so markers resolve. */
export async function exportFlowViewportPng(frame: DiagramExportFrame): Promise<string> {
  const stage = document.createElement('div');
  stage.style.cssText = `position:fixed;left:-100000px;top:0;width:${frame.width}px;height:${frame.height}px;overflow:hidden;pointer-events:none;`;
  const clone = frame.viewport.cloneNode(true) as HTMLElement;
  clone.setAttribute('xmlns', XML_NS);
  clone.style.position = 'relative';
  clone.style.width = `${frame.width}px`;
  clone.style.height = `${frame.height}px`;
  clone.style.transformOrigin = '0 0';
  clone.style.transform = `translate(${frame.translateX}px, ${frame.translateY}px) scale(${frame.zoom})`;
  clone.style.backgroundColor = 'transparent';
  stage.appendChild(clone);
  document.body.appendChild(stage);
  try {
    inlineStyles(clone);
    clone.style.backgroundColor = 'transparent';
    const edgeSvgs = [...clone.querySelectorAll('.react-flow__edges svg')];
    edgeSvgs.forEach((edgeSvg) => {
      if (!(edgeSvg instanceof SVGSVGElement)) return;
      edgeSvg.setAttribute('width', String(frame.flowExtent));
      edgeSvg.setAttribute('height', String(frame.flowExtent));
      edgeSvg.style.width = `${frame.flowExtent}px`;
      edgeSvg.style.height = `${frame.flowExtent}px`;
      edgeSvg.style.overflow = 'visible';
      edgeSvg.style.position = 'absolute';
      edgeSvg.style.left = '0';
      edgeSvg.style.top = '0';
    });
    scrubTree(clone);
    const svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('xmlns', SVG_NS);
    svg.setAttribute('width', String(frame.width));
    svg.setAttribute('height', String(frame.height));
    svg.setAttribute('viewBox', `0 0 ${frame.width} ${frame.height}`);
    const backdrop = document.createElementNS(SVG_NS, 'rect');
    backdrop.setAttribute('width', '100%');
    backdrop.setAttribute('height', '100%');
    backdrop.setAttribute('fill', frame.background);
    svg.appendChild(backdrop);
    const foreignObject = document.createElementNS(SVG_NS, 'foreignObject');
    foreignObject.setAttribute('x', '0');
    foreignObject.setAttribute('y', '0');
    foreignObject.setAttribute('width', String(frame.width));
    foreignObject.setAttribute('height', String(frame.height));
    foreignObject.appendChild(clone);
    svg.appendChild(foreignObject);
    if (edgeSvgs.length > 0) {
      const group = document.createElementNS(SVG_NS, 'g');
      group.setAttribute('transform', `translate(${frame.translateX} ${frame.translateY}) scale(${frame.zoom})`);
      edgeSvgs.forEach((edgeSvg) => group.appendChild(edgeSvg));
      svg.appendChild(group);
    }
    const markup = scrubXmlText(new XMLSerializer().serializeToString(svg));
    const image = await loadImage(`data:image/svg+xml;charset=utf-8,${encodeURIComponent(markup)}`);
    const canvas = document.createElement('canvas');
    canvas.width = frame.width;
    canvas.height = frame.height;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('ER diagram canvas is unavailable');
    context.fillStyle = frame.background;
    context.fillRect(0, 0, frame.width, frame.height);
    context.drawImage(image, 0, 0, frame.width, frame.height);
    return canvas.toDataURL('image/png');
  } finally {
    stage.remove();
  }
}

export function downloadDataUrl(dataUrl: string, filename: string) {
  const link = document.createElement('a');
  link.href = dataUrl;
  link.download = filename;
  link.click();
}
