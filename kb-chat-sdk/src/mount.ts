import { createRoot } from 'react-dom/client';
import { ChatWidget } from './ChatWidget';
import type { ChatConfig } from './types';

/**
 * 挂载聊天组件到指定 DOM 容器。
 */
export function mount(selector: string, config: ChatConfig): void {
  const container = document.querySelector(selector);
  if (!container) {
    console.warn('[OHKB Chat SDK] Container not found:', selector);
    return;
  }

  const root = createRoot(container);
  root.render(<ChatWidget config={config} />);
}
