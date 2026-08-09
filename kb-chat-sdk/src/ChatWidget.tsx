import { useState, useRef, useEffect, useCallback } from 'react';
import type { ChatConfig, ChatMessage, Citation } from './types';

interface Props {
  config: ChatConfig;
}

/**
 * 聊天窗口主组件。
 * 固定在右下角，点击展开/收起。
 */
export function ChatWidget({ config }: Props) {
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [feedbackOpen, setFeedbackOpen] = useState<string | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // 自动滚动到底部
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const sendMessage = useCallback(async () => {
    if (!input.trim() || loading) return;

    const userMsg: ChatMessage = {
      id: crypto.randomUUID(),
      role: 'user',
      content: input.trim(),
    };

    setMessages(prev => [...prev, userMsg]);
    setInput('');
    setLoading(true);

    // 占位 AI 消息（流式更新）
    const aiMsgId = crypto.randomUUID();
    const aiMsg: ChatMessage = {
      id: aiMsgId,
      role: 'assistant',
      content: '',
    };
    setMessages(prev => [...prev, aiMsg]);

    try {
      const response = await fetch(`${config.apiBase}/api/chat/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${config.token}`,
          'X-Channel': 'web',
          'X-User-Id': config.token, // 简化：由后端从 JWT 解析
        },
        body: JSON.stringify({
          question: userMsg.content,
          history: messages.map(m => ({ role: m.role, content: m.content })),
          pageContext: config.pageContext,
        }),
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      const reader = response.body?.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let citations: Citation[] = [];

      while (reader) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (line.startsWith('data:')) {
            const data = line.slice(5).trim();
            if (data.startsWith('{')) {
              // metadata 事件
              try {
                const meta = JSON.parse(data);
                citations = meta.citations || [];
              } catch {}
            } else {
              // token 事件
              setMessages(prev =>
                prev.map(m =>
                  m.id === aiMsgId ? { ...m, content: m.content + data } : m
                )
              );
            }
          }
        }
      }

      // 流结束，附加引用
      setMessages(prev =>
        prev.map(m =>
          m.id === aiMsgId ? { ...m, content: m.content, citations } : m
        )
      );

    } catch (err) {
      setMessages(prev =>
        prev.map(m =>
          m.id === aiMsgId
            ? { ...m, content: '抱歉，回答生成失败，请稍后重试。'}
            : m
        )
      );
    } finally {
      setLoading(false);
    }
  }, [input, loading, messages, config]);

  return (
    <>
      {/* 浮动触发按钮 */}
      <button
        onClick={() => setOpen(!open)}
        className="fixed bottom-4 right-4 z-50 w-14 h-14 bg-blue-600 text-white rounded-full shadow-lg hover:bg-blue-700 transition-all flex items-center justify-center text-2xl"
        title="AI 助手"
      >
        {open ? '✕' : '💬'}
      </button>

      {/* 聊天窗口 */}
      {open && (
        <div className="fixed bottom-20 right-4 z-50 w-96 h-[500px] bg-white rounded-xl shadow-2xl border border-gray-200 flex flex-col">
          {/* 头部 */}
          <div className="px-4 py-3 border-b border-gray-200 flex items-center justify-between">
            <div>
              <h3 className="font-semibold text-gray-900">AI 助手</h3>
              <p className="text-xs text-gray-500">职业健康检测知识库问答</p>
            </div>
            <button
              onClick={() => setOpen(false)}
              className="text-gray-400 hover:text-gray-600"
            >
              ✕
            </button>
          </div>

          {/* 消息列表 */}
          <div className="flex-1 overflow-y-auto p-4 space-y-4">
            {messages.length === 0 && (
              <div className="text-center text-gray-400 py-8">
                <p className="text-4xl mb-2">👋</p>
                <p className="font-medium">你好！我是 AI 助手</p>
                <p className="text-sm mt-1">可以问我系统操作的任何问题</p>
                <div className="mt-3 space-y-1 text-sm">
                  <button
                    onClick={() => setInput('项目申报的提交流程是什么？')}
                    className="block w-full text-left px-3 py-1.5 rounded-lg hover:bg-gray-100 text-gray-600"
                  >
                    💡 项目申报的提交流程是什么？
                  </button>
                  <button
                    onClick={() => setInput('采样前需要做什么准备？')}
                    className="block w-full text-left px-3 py-1.5 rounded-lg hover:bg-gray-100 text-gray-600"
                  >
                    💡 采样前需要做什么准备？
                  </button>
                </div>
              </div>
            )}
            {messages.map(msg => (
              <div
                key={msg.id}
                className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}
              >
                <div
                  className={`max-w-[80%] rounded-lg px-4 py-2 ${
                    msg.role === 'user'
                      ? 'bg-blue-600 text-white'
                      : 'bg-gray-100 text-gray-900'
                  }`}
                >
                  <p className="text-sm whitespace-pre-wrap">{msg.content || (loading ? '...' : '')}</p>
                  {msg.citations && msg.citations.length > 0 && (
                    <div className="mt-2 pt-2 border-t border-gray-300/50">
                      <p className="text-xs text-gray-500 mb-1">📎 参考来源：</p>
                      {msg.citations.map((c, i) => (
                        <p key={i} className="text-xs text-blue-500 truncate">
                          {c.verified ? '✅' : '⚠️'} {c.title}
                        </p>
                      ))}
                    </div>
                  )}
                  {msg.role === 'assistant' && msg.content && !loading && (
                    <div className="mt-2 flex space-x-2">
                      <button className="text-xs text-gray-400 hover:text-green-500">👍</button>
                      <button
                        className="text-xs text-gray-400 hover:text-red-500"
                        onClick={() => setFeedbackOpen(msg.id)}
                      >
                        👎
                      </button>
                    </div>
                  )}
                </div>
              </div>
            ))}
            <div ref={messagesEndRef} />
          </div>

          {/* 输入区域 */}
          <div className="p-4 border-t border-gray-200">
            <div className="flex items-center space-x-2">
              <input
                type="text"
                value={input}
                onChange={e => setInput(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && sendMessage()}
                placeholder="输入问题..."
                disabled={loading}
                className="flex-1 px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:bg-gray-100"
              />
              <button
                onClick={sendMessage}
                disabled={loading || !input.trim()}
                className="px-4 py-2 bg-blue-600 text-white text-sm rounded-lg hover:bg-blue-700 disabled:bg-gray-300 disabled:cursor-not-allowed"
              >
                发送
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
