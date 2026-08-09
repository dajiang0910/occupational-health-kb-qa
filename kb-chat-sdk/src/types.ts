export interface PageContext {
  module: string;
  action?: string;
  pageTitle?: string;
}

export interface ChatConfig {
  apiBase: string;       // Spring Boot 后端地址，如 https://kb.example.com
  token: string;          // JWT Token（5 分钟有效期）
  pageContext?: PageContext;
}

export interface Citation {
  articleId: string;
  title: string;
  snippet: string;
  verified: boolean;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  citations?: Citation[];
  confidence?: number;
  feedback?: 'helpful' | 'unhelpful';
}
