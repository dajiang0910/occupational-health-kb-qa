/**
 * OHKB Chat SDK — 嵌入式聊天组件入口。
 *
 * 使用方式（主 SaaS 页面）：
 * ```html
 * <div id="ohkb-chat-root"></div>
 * <script src="ohkb-chat-sdk.js"></script>
 * <script>
 *   OhkbChatSDK.mount('#ohkb-chat-root', {
 *     apiBase: 'https://kb.example.com',
 *     token: 'eyJhbG...',       // 主 SaaS 生成的 JWT
 *     pageContext: {
 *       module: 'project_application',
 *       action: 'submit_form',
 *     }
 *   });
 * </script>
 * ```
 */
export { mount } from './mount';
export type { ChatConfig, PageContext } from './types';
