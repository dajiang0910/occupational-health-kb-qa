# AI 知识库问答产品 PRD

> **版本**: v2.1（Java 21 + 异常处理 + 日志策略）
> **状态**: `ready-for-agent`（待开发）

## Problem Statement

职业健康检测技术服务全流程 SaaS 系统已上线运行，覆盖项目申报、现场调查、检测采样、评价报告编制到项目公示的全生命周期管理。系统功能模块多、业务链路长，客户在日常使用中频繁遇到操作问题。当前唯一的支持渠道是人工客服（工作时间、微信群），存在以下痛点：

1. **响应滞后**：人工客服无法覆盖非工作时间的咨询，客户在夜间或节假日遇到问题时只能等待。高峰期客服同时处理多个咨询，平均响应时间超过 30 分钟。
2. **重复劳动**：客服团队每天收到的咨询中，超过 60% 是重复的常见问题（如"怎么提交申报"、"报告审核流程是什么"）。客服人员反复回答相同问题，效率低下。
3. **知识散落**：系统操作手册、FAQ、培训视频、版本更新日志分散在多个渠道（PDF 文档、内部 Wiki、微信群文件），客户查找成本高，客服也需要切换多个来源才能给出完整答案。
4. **缺乏沉淀**：客服每天处理的问答没有被有效记录和结构化，新人培训依赖老员工口传心授，服务质量和一致性难以保证。
5. **群消息淹没**：企业微信外部客户群中，客户提问经常被其他消息淹没，客服容易遗漏，缺乏自动拾取和回复机制。

客户期望一个能够 7×24 小时即时响应、答案准确有据、操作简单的智能问答产品。

## Solution

构建一个基于 RAG（检索增强生成）的 AI 知识库问答产品，核心能力包括：

1. **知识库管理**：将系统操作手册、FAQ、版本更新日志等全部文档结构化入库，支持持续增删改。文档格式覆盖 PDF、Word、Markdown、富文本。结构感知分块策略确保复杂表格和多级标题的语义完整性。
2. **AI 智能问答（Web 端）**：客户在 SaaS 系统内嵌的聊天窗口直接用自然语言提问，AI 基于知识库实时生成回答，附带可信引用来源，支持多轮追问和 Token 预算管理下的上下文压缩。
3. **企业微信集成**：AI Bot 接入企业微信外部客户群，通过多信号融合（回复链检测 + 用户时间窗口 + 语义粘连判断）精准识别问题，支持 @ 唤起和智能意图识别，连续误触发自动降级。
4. **人机协同**：AI 无法回答的低置信度问题自动创建工单并转接人工客服。自建 3 状态工单系统，客服可一键将优质回答纳入知识库，知识化作为异步流程不阻塞工单关闭。
5. **分析与洞察**：问答数据分析面板，实时查看高频问题、知识库覆盖盲区、回答质量趋势。点踩反馈自动分类归因，驱动知识库持续迭代。

## Tech Stack

| 层级 | 技术 | 说明 |
|------|------|------|
| 后端框架 | Spring Boot 3.x + Spring MVC | 基于 Servlet 容器（Tomcat），传统线程模型 |
| AI 框架 | LangChain4j | RAG Pipeline、文档加载器、ChatMemory 管理 |
| 流式输出 | `SseEmitter` | Spring MVC 原生 SSE 支持，替代 WebFlux |
| LLM 提供者 | 阿里云百炼（DashScope） | OpenAI 兼容端点 + 显式 Prompt Caching |
| Embedding | 百炼 text-embedding-v3（1536 维） | 锁定百炼，`knowledge_articles` 表记录 `embedding_model` |
| 数据库 | PostgreSQL 15+ + pgvector | JDBC 驱动，HNSW 索引，与主数据库同实例 |
| 前端-管理后台 | Thymeleaf + HTMX + Tailwind CSS | 服务端渲染，知识库管理 / 工单 / 分析三大 Tab |
| 前端-聊天组件 | React 19（独立 SDK） | Vite 打包，iframe 嵌入主 SaaS，SSE 流式消费 |
| 文档解析 | Apache Tika + LangChain4j DocumentLoader | 结构感知分块，表格双存（MD+JSON） |
| 企业微信 | 企微 Bot API + 回调 Webhook | 消息收发、意图识别、限流保护 |
| 运行时 | Java 21（Virtual Threads / Pattern Matching / Switch Expression） | - |
| 构建工具 | Maven 3.9+ | - |
| 日志 | SLF4J + Logback | MDC 全链路追踪，JSON 结构化日志 |
| 异常处理 | `@ControllerAdvice` + 全局 ErrorCode 枚举 + 业务异常分层 | 统一错误响应 + 可审计日志 |

## User Stories

### 知识库管理

1. As a 知识库管理员，I want 通过管理后台上传文档（PDF、Word、Markdown、TXT），so that 系统能自动解析内容并纳入知识库。
2. As a 知识库管理员，I want 系统自动对上传的文档进行结构感知分块（识别标题层级、表格边界、语义段落）并生成向量嵌入，so that 复杂文档的语义完整性得到保留。
3. As a 知识库管理员，I want 手动创建、编辑、删除单条 Q&A（问答对），so that 可以精确补充知识库内容。
4. As a 知识库管理员，I want 按 SaaS 系统模块（项目申报、现场调查、检测采样、评价报告、项目公示、系统设置等）对知识条目进行分类标注，so that RAG 检索时可以按模块精准过滤。
5. As a 知识库管理员，I want 批量导入现有的 FAQ Excel 表格，so that 历史积累的问答能快速入库。
6. As a 知识库管理员，I want 设置知识条目的生效/失效日期，so that 针对特定版本或时间段的操作指引在过时后自动停用（标记为"⚠️ 可能已过时"但不隐藏）。
7. As a 知识库管理员，I want 查看每条知识被检索命中的次数和用户反馈，so that 可以判断哪些内容最常用、哪些需要改进。
8. As a 知识库管理员，I want 对单条知识设置标签（如"常见问题"、"高级功能"、"新功能"），so that 支持更灵活的内容组织和检索。
9. As a 知识库管理员，I want 预览文档解析后的分块结果（包括失败分块的明确标记），so that 确保切分质量符合预期且了解哪些片段解析失败。
10. As a 知识库管理员，I want 一键重新解析和重新嵌入某个文档，so that 文档更新后不需要删除重建。

### Web 端 AI 问答

11. As a SaaS 系统用户（客户），I want 在系统右下角点击打开聊天窗口，用自然语言输入问题，so that 可以随时随地获得帮助。
12. As a 客户，I want AI 回答以流式方式逐字展示（SSE），so that 不需要等待完整答案生成就能开始阅读。
13. As a 客户，I want 每个 AI 回答都附带经过校验的可信引用来源（点击可查看原文），so that 我可以验证答案的可靠性。
14. As a 客户，I want AI 回答中包含图片或截图（如果知识库有），so that 复杂操作指引更加直观。
15. As a 客户，I want 对 AI 回答点赞或点踩，so that 帮助系统识别回答质量。
16. As a 客户，I want 对不满意的回答提交反馈说明，系统自动分类归因（答案错误/知识缺失/难以理解/答非所问/其他），so that 管理员可以针对性改进。
17. As a 客户，I want 在同一会话中追问（多轮对话），AI 能够理解上下文（通过 Token 预算管理和摘要压缩保持长对话质量），so that 不需要每次都重复背景信息。
18. As a 客户，I want 看到 AI 推荐的相关问题（"你可能还想问"），so that 可以快速获取关联信息。
19. As a 客户，I want 当 AI 无法回答时，一键"转人工客服"（自动创建工单），so that 复杂问题不会被搁置。
20. As a 客户，I want 在聊天窗口内直接搜索知识库（关键词搜索），so that 可以主动查找而不仅是被动等 AI 回答。
21. As a 客户，I want 查看历史对话记录，so that 可以回顾之前的问答。
22. As a 客户，I want 在我的操作页面看到与当前模块相关的常见问题推荐（主 SaaS 通过 SDK 传 pageContext），so that 可以更快定位所需帮助。
23. As a 客户，I want 在任何页面看到悬浮的"帮助"按钮，点击直接打开该页面相关的操作指引，so that 不需要离开当前工作流。
24. As a 客户，I want AI 回答格式规范（有序列表、表格、**加粗重点**），so that 操作步骤和对比信息清晰易读。

### 企业微信集成

25. As a 客户，I want 在企业微信外部客户群中 @AI助手 并提问，so that 不需要离开企业微信就能获得答案。
26. As a 客户，I want AI 助手通过多信号融合（回复链检测、用户时间窗口、语义粘连）自动识别群聊中的问题，连续误触发 3 次后自动降级为"仅 @ 回复模式"，so that 自然对话中也能得到及时回复且不会骚扰群聊。
27. As a 客户，I want AI 助手的群聊回复附带引用来源链接，so that 可以点击查看详细文档。
28. As a 客户，I want AI 助手在无法回答时，自动创建工单并 @群内人工客服说明原因，so that 复杂问题能被及时接手。
29. As a 企业微信管理员，I want 在管理后台配置 Bot 接入哪些外部客户群，so that 可以控制 Bot 的服务范围。
30. As a 企业微信管理员，I want 设置 Bot 的自动回复规则（如工作时间回复、非工作时间告知客户等待），so that 客户有合理的响应预期。
31. As a 企业微信管理员，I want 查看 Bot 在每个群的历史回复记录，so that 可以审核回复质量和发现遗漏。
32. As a 企业微信管理员，I want 设置敏感词过滤，涉及商业机密或敏感数据的问题不自动回答，so that 信息安全得到保障。
33. As a 企业微信管理员，I want Bot 遇到被过滤的问题时，仅回复"此问题涉及敏感信息，请联系人工客服"，so that 客户不会觉得被忽视。
34. As a 客户，I want Bot 支持富文本回复（图文消息、链接卡片），so that 回答形式丰富、信息完整。
35. As a 客户，I want Bot 在群聊中支持快捷指令（如"帮助"查看功能列表、"转人工"一键转接），so that 操作更高效。
36. As a 客户，I want 同一个问题在群里多人追问时，Bot 通过用户级上下文线程（而非群级）给出上下文关联的回答，so that 不打断群聊的讨论流也不会混淆不同用户的话题。

### 人机协同

37. As a 人工客服，I want 在客服工作台看到 AI 转接过来的工单（包含完整上下文和 AI 已给出的部分回答），so that 接手时不需要重新询问客户。
38. As a 人工客服，I want 在回复客户后，一键将优质回答"纳入知识库"（自动生成 Q&A 条目草稿），so that 知识库持续增长。知识化流程异步进行，不阻塞工单关闭。
39. As a 人工客服，I want 看到 AI 回答的置信度评分（LLM 自评 0.4 + 检索 Top-1 相似度 0.6 加权），so that 低置信度的回答可以人工复核。
40. As a 人工客服，I want 收到知识库新增/更新的通知（浏览器通知 + 企微应用内消息），so that 及时了解系统操作变更。
41. As a 人工客服，I want 查看待回复工单队列（来自企业微信和 Web 端），按优先级排序，so that 高效处理工单。
42. As a 知识库管理员，I want 定期收到"未命中知识库"的问题汇总（按自动分类的失败原因分组），so that 可以有针对性补充内容。

### 数据分析与监控

43. As a 产品经理，I want 查看问答数据看板（日活提问数、回答满意率、高频问题 Top 10），so that 了解客户使用情况。
44. As a 产品经理，I want 查看知识库覆盖盲区报告（哪些问题经常被问但 AI 无法回答，按原因分类），so that 驱动文档和产品改进。
45. As a 产品经理，I want 按 SaaS 模块维度查看问题分布（哪个模块问题最多），so that 针对性优化模块的易用性。
46. As a 运维人员，I want 监控 RAG Pipeline 的响应时间（P50/P95/P99）和 LLM 调用延迟，so that 及时发现性能退化。
47. As a 运维人员，I want 设置告警规则（回答满意率低于阈值、响应时间超过阈值、LLM API 连续失败触发降级），so that 系统异常时第一时间收到通知。
48. As a 运维人员，I want 查看 API 调用量和 Token 消耗，so that 控制 AI 服务成本。

### 系统集成与安全

49. As a SaaS 系统管理员，I want AI 问答组件通过嵌入式 React SDK（iframe + JWT Token 透传鉴权）接入主系统，so that 不需要主系统做大量改造且安全可控。
50. As a SaaS 系统管理员，I want 知识库支持与主系统的版本更新日志自动同步（主 SaaS 发版时通过 CI/CD pipeline 推送变更），so that 每次系统升级后知识库自动标记相关条目为"待审核"。
51. As a 安全管理员，I want 所有 API 端点进行 JWT 身份认证和鉴权（5 分钟有效期），so that 知识库内容只能被授权用户访问。
52. As a 安全管理员，I want 用户数据隔离（不同客户的员工只能看到面向其角色的知识），so that 内部机密信息不外泄。
53. As a 安全管理员，I want 所有问答日志完整记录（可审计），so that 满足企业合规要求。

## Implementation Decisions

### 架构决策

**1. 独立 Spring Boot 应用**

知识库问答产品作为独立 Spring Boot 3.x 应用部署，通过嵌入式 React SDK（iframe）集成到主 SaaS 系统。两个系统独立迭代、独立扩缩容，故障隔离。

应用模块结构：

```
occupational-health-kb-qa/
├── kb-admin/                    # 管理后台（Thymeleaf + HTMX）
│   ├── controller/             # 知识库管理、工单、分析的 MVC Controller
│   └── templates/              # Thymeleaf 模板
├── kb-chat-sdk/                 # 嵌入式聊天组件（React + Vite，独立打包）
├── kb-core/                     # 核心业务逻辑
│   ├── rag/                    # RAG Pipeline（LangChain4j）
│   ├── knowledge/              # 知识库管理领域
│   ├── chat/                   # 对话管理领域
│   ├── ticket/                 # 工单领域
│   ├── wechat/                 # 企微集成
│   └── analytics/              # 分析领域
├── kb-infrastructure/           # 基础设施
│   ├── llm/                    # 百炼 API 封装（原生 HttpClient + cache_control）
│   ├── vectorstore/            # pgvector DAO
│   ├── document/               # Apache Tika + 结构感知分块
│   └── security/               # JWT 鉴权
└── kb-api/                      # REST API（供 React SDK 消费）
    └── controller/             # SSE 流式问答、知识库检索 API
```

**2. RAG Pipeline 核心切口（唯一新增切口）**

所有问答通道（Web Chat API、企业微信 Webhook）统一调用 `RagPipeline` 服务。Pipeline 接口定义：

```java
public interface RagPipeline {
    RagResult answer(RagRequest request);
}

// 输入
public record RagRequest(
    String question,              // 当前问题
    List<Message> history,        // 对话历史（已压缩）
    Map<String, String> filters,  // 可选过滤：module, tag, role
    String pageContext            // 主 SaaS 传入的当前页面上下文
) {}

// 输出
public record RagResult(
    String answer,                // Markdown 格式回答
    List<Citation> citations,     // 引用来源（含 articleId）
    double confidence,            // LLM 自评×0.4 + 检索 Top-1×0.6
    List<String> relatedQuestions,// 推荐问题
    List<String> articleIds,      // 命中的知识条目 ID，用于语义缓存精准失效
    boolean fallback              // 是否触发转人工
) {}
```

- 当 `confidence < threshold` 时，`fallback = true`，调用方负责创建工单
- `articleIds` 用于语义缓存层的精准失效

**3. Confidence 评分与问题分类**

**评分公式**: `confidence = LLM 自评 × 0.4 + 检索 Top-1 余弦相似度 × 0.6`（检索主导，客观优先）。

**问题类型差异化阈值**（意图识别 + 问题分类合并为一次 LLM 调用）：

| 问题类型 | 特征 | 阈值 | 说明 |
|---------|------|------|------|
| 事实查询型 | "XX 功能在哪个菜单"、"某字段含义" | 0.80 | 检索精度高，阈值严格 |
| 操作指引型 | "怎么提交申报"、"审核流程是什么" | 0.65 | 需多文档组合，中等阈值 |
| 故障排查型 | "为什么报告审核不通过" | 0.50 | 可能跨模块，给予更多发挥空间 |

LLM 调用 structured output 同时返回 `{intent, category}`。

**4. LangChain4j RAG Pipeline 实现**

```
用户问题
  → 语义缓存检查（双层阈值 0.92/0.85）
  → ChatLanguageModel 分类（意图 + 问题类型，一次调用）
  → EmbeddingStore<TextSegment> 向量检索（pgvector HNSW Top-20）
  → [条件] Top-1 相似度 < 0.8 → Reranker 重排序（Cross-encoder）
  → Token 预算管理（文档截断、ChatMemory 压缩）
  → StreamingChatLanguageModel 流式生成
    → 注入 cache_control（原生 HTTP 层，见 §11）
  → 引用校验（逐条对比原文）
  → 返回 RagResult
```

LangChain4j 关键组件映射：

| PRD 概念 | LangChain4j 组件 |
|---------|-----------------|
| 向量检索 | `PgVectorEmbeddingStore` + HNSW 索引 |
| 文档分块 | `DocumentSplitter` + 自定义结构感知 `TextSegment` |
| Embedding | 百炼 `EmbeddingModel`（OpenAI 兼容端点） |
| LLM 生成 | `StreamingChatLanguageModel`（OpenAI 兼容端点 + HTTP 拦截器注入 cache_control） |
| 对话记忆 | `ChatMemory` + `MessageWindowChatMemory` / `TokenWindowChatMemory` |
| 重排序 | `CompressingQueryTransformer` + 条件调用 Cross-encoder |
| 内容检索 | `EmbeddingStoreContentRetriever` + `WebSearchContentRetriever` |

**5. Prompt Caching 显式实现（已验证 ✅）**

百炼显式缓存已通过技术验证：cache_creation_input_tokens > 0 ✅，cached_tokens 命中率 98.9% ✅。

LangChain4j 不直接支持 `cache_control`，在 HTTP 层通过 OkHttp 拦截器注入：

```java
// 原型：在发给百炼的 /chat/completions 请求中注入 cache_control
// cache_control 标记打在 system prompt 和热文档块上
// 格式参考百炼文档：content[] 数组元素添加 "cache_control": {"type": "ephemeral"}
```

三层缓存架构：
```
L1 精确缓存：HashMap<String, CachedAnswer>（内存，TTL 30min）
L2 语义缓存：Embedding 相似度 > 0.92（pgvector 存 question_embedding，双层阈值）
L3 LLM + PC：显式 cache_control 标记（System Prompt + 热文档，缓存命中成本降低 90%）
```

**6. Token 预算分配与上下文压缩**

LLM 上下文窗口总预算 6000 tokens：

| 组件 | 预算 | 超出处理 |
|------|------|---------|
| System Prompt + 角色设定 | ~500 tokens | 固定，依靠 Prompt Caching |
| 检索文档（Top-5 chunks） | ~2000 tokens | 超出则截断至最相关的 3 个 |
| ChatMemory（对话历史） | ~2000 tokens | 超出触发 `TokenWindowChatMemory` 自动滑动 + 摘要 |
| 当前问题 + 回答预留 | ~1500 tokens | - |

摘要压缩策略：LangChain4j 的 `ChatMemory` 配合自定义 `ChatMemoryStore`，历史消息超 2000 tokens 时触发摘要（一次短 LLM 调用压缩为 ~200 tokens）。

**7. 语义缓存双层阈值**

```
相似度 > 0.92  → 直接返回缓存结果（高置信命中）
相似度 0.85-0.92 → 轻量 LLM 校验（"这两个问题是否等价?"，<50 tokens）
相似度 < 0.85  → 不走缓存，完整 RAG Pipeline
```

缓存记录存储引用的 `article_ids`，知识条目更新/删除时通过 `@Transactional` 精准失效关联缓存。缓存 TTL 与知识库版本关联。

**8. 引用校验**

RAG Pipeline 输出阶段增加引用校验步骤（一次 LLM 调用全量校验所有引用）：

```
回答生成 → 提取所有 citation → 逐条对比("以下陈述是否被这段原文支持? YES/NO/PARTIALLY")
          → 不支持的引用标记"⚠️ 此引用可能不完全准确"或移除
```

**9. 企业微信集成方案**

- 使用企微"客户群"API + 回调 Webhook 实现消息收发
- Spring MVC `@RestController` 接收企微回调（XML/JSON 解析）
- 消息延迟预算：企微端硬超时 5 秒，超时先返回"正在查询..."占位消息
- Reranker 仅在检索 Top-1 相似度 < 0.8 时启用，控制延迟

**10. 企微群消息意图识别——多信号融合**

```
信号 1（回复链检测）：消息 reply_to 指向非 Bot → 100% 排除，绝对不回应
信号 2（用户时间窗口）：用户 5 分钟内刚与 Bot 交互过 → 窗口内消息高概率路由到该用户线程
信号 3（模型二分类）  ：前两个信号不明确时，走二分类模型，阈值 0.85+（宁可漏答不可误答）

三个信号 OR 逻辑，任一高置信命中即触发应答。
连续误触发 3 次（10 分钟内）→ 自动降级为"仅 @ 回复模式"，管理员可手动恢复。
```

**11. 企微群多用户上下文管理——用户级线程**

Bot 为每个群维护 `ConcurrentHashMap<String, UserThread>`（`user_id → ThreadContext`），而非群级单一上下文：

```
路由决策优先级：
1. 回复链（reply_to 指向 Bot 回复的消息链）→ 100% 命中
2. 用户窗口（5 分钟内该用户有活跃线程）→ 路由到自己的线程
3. 语义粘连（有代词/省略主语 + 与历史线程相似度 > 0.85）→ 唤醒旧线程
4. 默认 → 新线程
```

线程数据定期持久化到 PostgreSQL（`conversation_context` 表），服务重启后可恢复。

**12. 企微端延迟预算与请求链路**

端到端延迟目标 ≤5 秒（企微硬超时）。链路优化后的调用次数：

- 合并调用：意图识别 + 问题分类 → 1 次 LLM
- 核心链路：pgvector 检索 → Reranker（按需）→ LLM 流式生成 → 1 次 LLM
- 最坏情况：2 次 LLM + 1 次 pgvector 查询 + 1 次 Reranker，5 秒预算可行

**13. 企微滥用防护——三层限流**

| 层级 | 机制 | 阈值 | 动作 |
|------|------|------|------|
| 用户级 | Guava RateLimiter（per user_id） | 3 次/分钟 | 超过 → 冷却 5 分钟，规则回复 |
| 群级 | Guava RateLimiter（per group_id） | 10 次/分钟 | 超过 → 降级为纯检索模式 5 分钟 |
| 全局级 | Spring AOP `@RateLimiter` 注解 | 可配置 QPS | 超过 → 排队，"系统繁忙" |

### 模块划分

**14. `kb-knowledge`（知识库管理模块）**

Spring MVC Controller + Service + Repository 三层。提供文档上传、结构感知解析、分块、嵌入、存储、CRUD 的完整能力。

- **文档分块策略**：基于 LangChain4j `DocumentSplitter` + 自定义结构感知切分器——先按标题层级（H1→H2→H3）拆成段落组，再按语义完整性细分
- **表格处理**：Apache Tika 预解析 → 表格区域识别 → 每个表格同时存储两份：chunk 正文用 Markdown 表格（LLM 友好），metadata 用 JSON（检索过滤、保留合并单元格语义）
- **文档解析容错**：部分成功策略——失败的分块不阻塞其余分块，入库时 `chunk_status = 'parse_failed'`，管理后台文档详情页明确标红显示跳过片段

**15. `kb-rag`（检索生成管线）**

封装 RAG Pipeline 全流程：检索→重排序→提示词组装→LLM 生成→引用校验→后处理。

核心类：
- `RagPipeline` — 入口接口
- `RagPipelineImpl` — 实现（组装各步骤，返回 `RagResult`）
- `PgVectorRetriever` — pgvector 向量检索 DAO
- `CacheControlInterceptor` — OkHttp 拦截器，注入 `cache_control` 标记
- `CitationVerifier` — 引用校验，逐条对比原文
- `ConfidenceCalculator` — 置信度计算（LLM 自评×0.4 + 检索×0.6）

**16. `kb-chat`（对话服务）**

管理多轮对话状态、Token 预算、摘要压缩触发、会话持久化。

- `ChatMemoryStore`：LangChain4j 的 `ChatMemory` + PostgreSQL 持久化
- 流式输出：`StreamingChatLanguageModel` → `SseEmitter`（Spring MVC）
- 对话窗口管理：每 6 轮触发摘要压缩

**17. `kb-wechat`（企微机器人）**

企微 Webhook 接收、多信号意图识别、用户线程管理、限流保护、消息去重。

- `WechatCallbackController`：`@RestController`，处理企微 GET（URL 验证）+ POST（消息推送）
- `IntentDetector`：多信号融合逻辑（回复链 + 用户窗口 + 二分类）
- `GroupContextManager`：用户级线程状态管理
- 自动降级状态机：`NORMAL ↔ AT_ONLY_MODE`（枚举 + Spring StateMachine 或简单状态枚举）

**18. `kb-ticket`（工单服务）**

自建轻量工单系统，3 状态状态机：`PENDING → CLAIMED → RESOLVED`。

- `TicketController`：管理后台 Thymeleaf 页面的 MVC Controller
- 工单创建时附带完整上下文（原始问题、AI 最佳尝试、检索文档、confidence）
- 客服回复后通过 `EventPublisher`（Spring Events）异步同步到原渠道
- 知识化流程异步：客服标记 → `@Async` 创建知识条目草稿 → `ApplicationEvent` 通知管理员

**19. `kb-analytics`（分析模块）**

问答日志聚合、看板数据查询、告警规则引擎。

- Spring `@Scheduled` 定时任务：每小时聚合问答日志 → 看板数据表
- 告警引擎：Micrometer + Spring Actuator 暴露 Metrics，Prometheus 采集，Alertmanager 告警
- LLM API 可用性监控：连续 3 次超时/错误 → 自动触发降级（30 秒探活恢复）

**20. 客服工作台**

管理后台 Thymeleaf 独立 Tab（`/admin/tickets`），与知识库管理（`/admin/kb`）、数据分析（`/admin/analytics`）并列。

- 待处理工单队列（按 priority 排序）
- 工单详情（对话历史 + AI 上下文）
- 回复输入（自动同步到原渠道）
- 一键纳入知识库按钮

### 数据模型

**21. 知识条目（knowledge_articles）**

```sql
CREATE TABLE knowledge_articles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title TEXT NOT NULL,
    content TEXT NOT NULL,          -- Markdown 格式（LLM 友好）
    content_json JSONB,             -- 结构化 JSON（表格数据，检索过滤用）
    category VARCHAR(50) NOT NULL,  -- project_application, field_survey, testing_sampling,
                                    -- evaluation_report, project_publication, system_settings, general
    tags TEXT[],
    source_type VARCHAR(20) NOT NULL, -- manual, document_import, customer_service
    source_doc_id UUID REFERENCES imported_documents(id),
    chunk_index INTEGER,
    chunk_status VARCHAR(20) DEFAULT 'ok', -- ok, parse_failed
    embedding VECTOR(1536),
    embedding_model VARCHAR(50) NOT NULL DEFAULT 'text-embedding-v3',
    effective_from TIMESTAMP NOT NULL DEFAULT NOW(),
    effective_until TIMESTAMP,
    is_deprecated BOOLEAN DEFAULT FALSE,
    hit_count INTEGER DEFAULT 0,
    helpful_count INTEGER DEFAULT 0,
    unhelpful_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_articles_category ON knowledge_articles(category);
CREATE INDEX idx_articles_embedding ON knowledge_articles USING hnsw (embedding vector_cosine_ops);
```

**22. 对话（conversations）**

```sql
CREATE TABLE conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel VARCHAR(20) NOT NULL,    -- web, wechat_group
    external_id TEXT,                 -- 企微群 ID 或 Web session ID
    user_id TEXT,
    status VARCHAR(20) DEFAULT 'active', -- active, transferred_to_human, closed
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
```

**23. 消息（messages）**

```sql
CREATE TABLE messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES conversations(id),
    role VARCHAR(20) NOT NULL,        -- user, assistant, system
    content TEXT NOT NULL,
    citations JSONB,                  -- [{articleId, title, snippet, verified: bool}]
    confidence DOUBLE PRECISION,      -- LLM 自评×0.4 + 检索 Top-1×0.6
    feedback VARCHAR(20),             -- helpful, unhelpful
    feedback_category VARCHAR(30),    -- wrong_answer, missing_knowledge, hard_to_understand, irrelevant, other
    feedback_note TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);
```

**24. 企微群配置（wechat_groups）**

```sql
CREATE TABLE wechat_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id TEXT NOT NULL UNIQUE,    -- 企微群 ID
    group_name TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    auto_reply_mode VARCHAR(20) DEFAULT 'smart_detect', -- at_only, smart_detect, auto_degraded
    degraded_at TIMESTAMP,
    degraded_reason TEXT,
    consecutive_mistriggers INTEGER DEFAULT 0,
    working_hours_only BOOLEAN DEFAULT FALSE,
    working_hours_start TIME,
    working_hours_end TIME,
    sensitive_keywords TEXT[],
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
```

**25. 导入文档（imported_documents）**

```sql
CREATE TABLE imported_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filename TEXT NOT NULL,
    file_type VARCHAR(10) NOT NULL,   -- pdf, docx, md, txt, xlsx
    original_size BIGINT,
    status VARCHAR(20) DEFAULT 'uploading', -- uploading, parsing, chunking, embedding, completed, failed
    chunk_count INTEGER DEFAULT 0,
    failed_chunk_count INTEGER DEFAULT 0,
    error_details JSONB,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
```

**26. 工单（support_tickets）**

```sql
CREATE TABLE support_tickets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID REFERENCES conversations(id),
    source_message_id UUID REFERENCES messages(id),
    channel VARCHAR(20) NOT NULL,     -- web, wechat_group
    status VARCHAR(20) DEFAULT 'pending', -- pending, claimed, resolved
    priority VARCHAR(10) DEFAULT 'normal', -- low, normal, high
    assigned_to TEXT,
    ai_answer_snapshot TEXT,
    retrieved_articles UUID[],
    resolution_note TEXT,
    knowledge_article_created UUID,
    claimed_at TIMESTAMP,
    resolved_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);
```

**27. 语义缓存（semantic_cache）**

```sql
CREATE TABLE semantic_cache (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    question_embedding VECTOR(1536),
    question_text TEXT NOT NULL,
    answer_text TEXT NOT NULL,
    citations JSONB,
    article_ids UUID[],
    hit_count INTEGER DEFAULT 1,
    last_hit_at TIMESTAMP DEFAULT NOW(),
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_semantic_cache_embedding ON semantic_cache USING hnsw (question_embedding vector_cosine_ops);
```

### API 契约

**28. 知识库管理 API（/api/kb/...）——供管理后台 Thymeleaf 消费**

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/kb/documents/upload` | 上传文档（MultipartFile），返回 document_id，`@Async` 后台解析 |
| GET | `/api/kb/documents` | 文档列表（Pageable 分页、status 筛选） |
| GET | `/api/kb/documents/{id}` | 文档详情及分块预览（含失败分块标记） |
| DELETE | `/api/kb/documents/{id}` | 删除文档及其关联知识条目 + 精准失效语义缓存 |
| POST | `/api/kb/articles` | 手动创建知识条目 |
| PUT | `/api/kb/articles/{id}` | 更新知识条目（精准失效语义缓存） |
| DELETE | `/api/kb/articles/{id}` | 删除知识条目 + 精准失效语义缓存 |
| GET | `/api/kb/articles` | 知识条目列表（分类、标签、关键词筛选） |
| POST | `/api/kb/articles/batch-import` | 批量导入 Q&A（MultipartFile Excel） |
| PUT | `/api/kb/documents/{id}/reprocess` | 重新解析和嵌入文档 |

**29. 问答 API（/api/chat/...）——供 React SDK 消费**

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/stream` | SSE 流式问答（`SseEmitter`），接收 pageContext 做模块过滤 |
| POST | `/api/chat/search` | 知识库关键词搜索（非 AI） |
| GET | `/api/chat/conversations` | 用户历史对话列表 |
| GET | `/api/chat/conversations/{id}` | 对话详情 |
| POST | `/api/chat/messages/{id}/feedback` | 提交回答反馈（`@Async` LLM 自动分类归因） |

**30. 企业微信 API（/api/wechat/...）**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/wechat/callback` | 企微回调 URL 验证 |
| POST | `/api/wechat/callback` | 接收企微消息推送（多信号意图识别 + 三层限流） |
| GET | `/api/wechat/groups` | 群配置列表 |
| PUT | `/api/wechat/groups/{id}` | 更新群配置（含 auto_reply_mode 手动恢复） |
| GET | `/api/wechat/groups/{id}/history` | 群对话历史 |

**31. 分析 API（/api/analytics/...）——供管理后台仪表盘消费**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/analytics/dashboard` | 看板数据（日活、满意率、高频问题 Top 10） |
| GET | `/api/analytics/gaps` | 知识盲区报告（按反馈分类归因分组） |
| GET | `/api/analytics/module-stats` | 按模块统计 |
| GET | `/api/analytics/performance` | 响应时间分布 + LLM API 可用性 |

**32. 工单 API（/api/tickets/...）**

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/tickets` | 工单列表（按状态、优先级筛选） |
| GET | `/api/tickets/{id}` | 工单详情（含对话上下文） |
| PUT | `/api/tickets/{id}/claim` | 认领工单 |
| PUT | `/api/tickets/{id}/resolve` | 解决工单，可选标记"纳入知识库" |
| POST | `/api/tickets/{id}/knowledge` | `@Async` 将工单回答转为知识条目草稿 |

**33. 版本同步 API（/api/kb/...）**

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/kb/version-sync` | 主 SaaS 发版时推送变更日志，标记相关条目 `is_deprecated = true` |

### 交互细节

**34. 管理后台（Thymeleaf + HTMX）**

三个独立 Tab：
- `/admin/kb`：知识库管理（文档上传、条目 CRUD、分类筛选、分块预览）
- `/admin/tickets`：客服工作台（待处理队列、工单详情、回复输入、纳入知识库）
- `/admin/analytics`：数据分析看板（图表用 Chart.js，HTMX 局部刷新）

页面交互用 HTMX 实现局部刷新（`hx-get`、`hx-post`），避免整页跳转。复杂图表（看板）用 Thymeleaf 注入初始 JSON 数据 + Chart.js 渲染。

**35. 嵌入式聊天组件（React SDK）**

独立 Vite 项目（`kb-chat-sdk/`），打包为单个 JS bundle + CSS，通过 iframe 嵌入主 SaaS。

- JWT Token 透传鉴权（5 分钟有效期，主 SaaS 生成，通过 iframe URL 参数传入）
- SSE 流式消费：`EventSource` → 逐字渲染 Markdown
- 主 SaaS 通过 `postMessage` 传入 `pageContext`（`{module, action, pageTitle}`）
- 初始状态：欢迎语 + 根据 pageContext 推荐常见问题
- 消息气泡：区分用户/AI，AI 消息下方显示校验后引用 + 反馈按钮
- 点踩后弹出分类选择 + 可选文字

**36. 企业微信消息流**

- @ 唤起：用户 @AI助手 → "正在查询..."占位 → 5 秒内编辑为完整回答
- 智能识别：多信号融合判断 → 回复
- 连续误触发 → 自动降级为 `at_only`，Bot 在群内发送降级通知
- 敏感词命中 → 预设话术，不调 LLM，`@Async` 通知管理员
- 低置信度 → 回复"暂时无法回答，已转接人工客服 @张三"，自动创建工单

**37. 人机协同完整工作流**

```
客户提问 → RagPipeline → confidence >= threshold?
  ├─ YES → 返回回答（含校验引用）→ 客户点踩? → @Async LLM 自动分类归因 → 管理员审查
  └─ NO  → 自动创建工单(PENDING)
           → ApplicationEvent 发布 → 浏览器通知 + 企微通知
           → 客服认领(CLAIMED)
           → 客服回复 → EventPublisher 同步到原渠道
           → 客服解决(RESOLVED)
           → [可选] @Async 纳入知识库 → 创建知识条目草稿 → 不阻塞工单关闭
```

### 技术选型详情

**38. Embedding 模型**

锁定百炼 `text-embedding-v3`，向量维度 1536。`knowledge_articles.embedding_model` 字段记录模型版本，支持未来渐进式迁移（新旧模型并存，按需逐条迁移）。

**39. LLM 提供者**

支持多提供者切换（百炼 Qwen / OpenAI GPT / Claude），通过 `application.yml` 的 `llm.provider` 配置项控制。默认使用百炼。

**40. 向量数据库**

PostgreSQL 15+ + pgvector。HNSW 索引。JDBC 驱动，成熟的 Spring Data JPA / `JdbcTemplate` 支持。在 <100 万条规模下性能媲美专用向量数据库。

**41. 文档解析**

LangChain4j 的 `ApacheTikaDocumentLoader` + 自定义 `TextSplitter`：

- PDF：Apache PDFBox（Tika 集成）+ 布局分析
- Word：Apache POI（Tika 集成），结构感知（识别标题层级和表格边界）
- Excel：Apache POI
- Markdown：直接读取
- 扫描版 PDF：预留 OCR 扩展点（Tesseract 集成）
- 表格：同时输出 Markdown + JSON

**42. 嵌入式 SDK 鉴权——JWT Token 透传**

```
Main SaaS（已有登录状态）
  → 生成短期 JWT（5 分钟有效，HMAC-SHA256 签名）
    {user_id, role, tenant_id, exp}
  → 加载 iframe（?token=<JWT>）
    → React SDK 解析 URL 参数
      → 所有 API 请求 Header: Authorization: Bearer <JWT>
        → Spring Security Filter 验证 JWT → 建立 SecurityContext
```

- 主 SaaS 与知识库产品共享 JWT 签名密钥（`jwt.secret` 配置）
- Spring Security `OncePerRequestFilter` 拦截 `/api/**` 路径鉴权
- Thymeleaf 管理后台使用独立的 Session 认证体系（不依赖主 SaaS JWT）

**43. 页面上下文（pageContext）**

主 SaaS → iframe `postMessage`：

```json
{"module": "project_application", "action": "submit_form", "pageTitle": "项目申报-提交"}
```

React SDK 监听 `message` 事件，将 `pageContext` 附加到每次 `/api/chat/stream` 请求中。

**44. 系统降级——三级自动降级**

```
Level 0（正常）  ：LLM + RAG 全功能
Level 1（LLM 不可用）：连续 3 次超时/错误 → 自动触发
                    → 纯检索模式：返回 Top-3 知识条目原文
                    → 语义缓存仍可用
Level 2（检索不可用）：Embedding API 也挂了
                    → 仅语义缓存兜底
Level 3（全部不可用）：缓存也未命中
                    → 降级消息："系统维护中，预计 X 分钟恢复，紧急问题请联系人工客服"
```

- 探活：`@Scheduled(fixedDelay = 30000)` 每 30 秒探测 LLM API，恢复后切回 Level 0
- 降级/恢复事件通过 `ApplicationEvent` 发布（通知运维 + 记录日志）
- 降级状态存储在 `ConcurrentHashMap<String, DegradeLevel>`（内存），无需持久化

**45. 知识库版本同步——推送模式**

主 SaaS CI/CD pipeline 最后一环 → `POST /api/kb/version-sync`，附带结构化变更日志：
- 模块名称、变更类型（新增/修改/废弃）、变更描述、新截图 URL、版本号

Spring Boot 收到后：
1. 匹配受影响的 `knowledge_articles`（模块 + 关键词，`JdbcTemplate` 查询）
2. 标记 `is_deprecated = true`
3. `@Async` 通知管理员
4. 已标记条目在问答中显示 "⚠️ 此内容可能已过时（当前系统版本 vX.Y）"

### 项目结构

```
occupational-health-kb-qa/
├── pom.xml                              # Maven 父 POM
├── kb-admin/                            # 管理后台模块
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/ohkb/admin/
│       │   ├── controller/              # KbController, TicketController, AnalyticsController
│       │   └── config/                  # WebMvcConfig, SecurityConfig
│       └── resources/
│           ├── templates/               # Thymeleaf 模板
│           │   ├── admin/
│           │   │   ├── kb/              # 知识库管理页面
│           │   │   ├── tickets/         # 工单页面
│           │   │   └── analytics/       # 分析看板
│           │   └── fragments/           # HTMX 局部刷新片段
│           ├── static/                  # CSS/JS 静态资源
│           └── application.yml
├── kb-chat-sdk/                         # 嵌入式聊天组件（独立 Vite/React 项目）
│   ├── package.json
│   ├── vite.config.ts
│   └── src/
│       ├── App.tsx                      # 聊天窗口主组件
│       ├── hooks/useChatStream.ts       # SSE 流式消费 Hook
│       └── components/                  # 消息气泡、输入框、引用卡片等
├── kb-core/                             # 核心业务逻辑
│   ├── pom.xml
│   └── src/main/java/com/ohkb/core/
│       ├── rag/                         # RagPipeline, PgVectorRetriever, CitationVerifier, ConfidenceCalculator
│       ├── knowledge/                   # KnowledgeService, DocumentParser, ChunkSplitter
│       ├── chat/                        # ChatService, ChatMemoryStore, TokenBudgetManager
│       ├── ticket/                      # TicketService, TicketStateMachine
│       ├── wechat/                      # WechatService, IntentDetector, GroupContextManager
│       └── analytics/                   # AnalyticsService, DashboardAggregator
├── kb-infrastructure/                   # 基础设施
│   ├── pom.xml
│   └── src/main/java/com/ohkb/infra/
│       ├── llm/                         # BailianClient, CacheControlInterceptor
│       ├── vectorstore/                 # PgVectorRepository
│       ├── document/                    # TikaLoader, StructureAwareSplitter
│       ├── cache/                       # SemanticCacheService, ExactMatchCache
│       └── security/                    # JwtAuthFilter, SecurityConfig
├── kb-api/                              # REST API（供 React SDK 消费）
│   ├── pom.xml
│   └── src/main/java/com/ohkb/api/
│       └── controller/                  # ChatController(SseEmitter), SearchController, FeedbackController
└── kb-boot/                             # Spring Boot 启动模块（聚合所有模块）
    ├── pom.xml
    └── src/main/java/com/ohkb/
        └── OhkbApplication.java         # @SpringBootApplication 入口
```

### 异常处理

**46. 异常分层体系**

三层异常体系确保故障可追踪、可恢复、可审计：

```
GlobalErrorCode（枚举，200+ 错误码）
├── BizException（业务异常，可恢复）
│   ├── KnowledgeNotFoundException
│   ├── DocumentParseException
│   ├── TicketStateException
│   └── WechatRateLimitException
├── IntegrationException（外部集成异常，可降级）
│   ├── LlmApiException（→ 触发自动降级）
│   ├── EmbeddingApiException（→ 触发自动降级）
│   └── WechatApiException（→ 消息队列重试）
└── SystemException（系统内部异常，不可恢复）
    ├── VectorStoreException
    ├── DatabaseException
    └── ConfigurationException
```

**错误码规范**：`GlobalErrorCode` 枚举，格式 `OHKB-{MODULE}-{CODE}`

| 模块 | 错误码范围 | 示例 |
|------|----------|------|
| 通用 | `OHKB-COMMON-0001~0099` | `OHKB-COMMON-0001` 未知错误 |
| 知识库 | `OHKB-KB-0100~0199` | `OHKB-KB-0100` 文档解析失败 |
| 对话 | `OHKB-CHAT-0200~0299` | `OHKB-CHAT-0200` RAG Pipeline 超时 |
| 企微 | `OHKB-WECHAT-0300~0399` | `OHKB-WECHAT-0300` 回调签名校验失败 |
| 工单 | `OHKB-TICKET-0400~0499` | `OHKB-TICKET-0400` 工单状态非法 |
| 鉴权 | `OHKB-AUTH-0500~0599` | `OHKB-AUTH-0500` JWT 过期 |
| LLM | `OHKB-LLM-0600~0699` | `OHKB-LLM-0600` 连续调用失败触发降级 |
| 文档 | `OHKB-DOC-0700~0799` | `OHKB-DOC-0700` 不支持的文件格式 |
| 限流 | `OHKB-RATE-0800~0899` | `OHKB-RATE-0800` 用户级限流触发 |

**`@ControllerAdvice` 全局异常处理**：

```java
// 原型：统一错误响应结构
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ErrorResponse> handleBiz(BizException e) {
        // 业务异常：HTTP 4xx，返回错误码 + 中文描述 + 日志（WARN 级别）
    }

    @ExceptionHandler(IntegrationException.class)
    public ResponseEntity<ErrorResponse> handleIntegration(IntegrationException e) {
        // 集成异常：HTTP 502，返回降级提示 + 触发 ApplicationEvent 通知运维 + 日志（ERROR 级别）
    }

    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ErrorResponse> handleSystem(SystemException e) {
        // 系统异常：HTTP 500，返回通用错误提示（不暴露内部细节） + 日志（FATAL 级别）
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception e) {
        // 未预期异常：HTTP 500，返回通用提示 + 完整堆栈日志（FATAL）+ 触发告警
    }
}

// 统一错误响应
public record ErrorResponse(
    String errorCode,    // OHKB-KB-0100
    String message,      // 中文描述
    String detail,       // 可选详细信息（仅开发/测试环境）
    String traceId,      // 链路追踪 ID
    Instant timestamp
) {}
```

**RAG Pipeline 异常处理**：Pipeline 内部异常不直接抛出到 Controller 层，而是在 Pipeline 内部捕获并转换为带 degrade 标记的 `RagResult`：

```java
try {
    // LLM 调用
} catch (LlmApiException e) {
    // 自动降级：标记 degrade + 触发告警 + 返回纯检索结果
    degradeMonitor.recordFailure(e);
    return RagResult.degraded(retrievedDocs);
}
```

**47. 企微消息处理异常**

企微回调是外部不可控输入，异常处理需要特别谨慎：

- 消息解析失败（非标准格式）→ WARN 日志 + 返回 HTTP 200（防止企微重试轰炸）
- 重复消息（`MsgId` 幂等检查）→ DEBUG 日志 + 静默丢弃
- LLM 调用超时（5 秒）→ 返回"正在查询..."占位消息 + `@Async` 补发回答
- 群消息频率超限 → 消息入队 + 顺序消费 + 超限丢弃写入 `dead_letter` 表

**48. 异步任务异常**

`@Async` 方法（文档解析、通知推送、反馈分类）异常不能静默吞掉：

```java
@Configuration
public class AsyncConfig implements AsyncConfigurer {
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) -> {
            // 写入 dead_letter 表 + ERROR 日志 + 可选重试（指数退避，最多 3 次）
        };
    }
}
```

### 日志策略

**49. 日志分层与格式**

使用 SLF4J + Logback，按级别输出到不同 Appender：

| 级别 | 内容 | 输出目标 | 保留期 |
|------|------|---------|--------|
| **FATAL** | 系统不可用（数据库宕机、LLM API 密钥失效） | `fatal.log` + 企微告警 | 90 天 |
| **ERROR** | 单次请求失败（LLM 调用异常、文档解析失败） | `error.log` + 告警阈值触发 | 60 天 |
| **WARN** | 降级触发、限流触发、缓存未命中率高、低置信度回答 | `warn.log` | 30 天 |
| **INFO** | 业务事件（对话创建、工单流转、知识条目变更、API 调用摘要） | `app.log` | 30 天 |
| **DEBUG** | 开发调试（仅 dev 环境启用） | `debug.log` | 7 天 |
| **TRACE** | 方法级追踪（仅本地开发） | 控制台 | - |

**JSON 结构化日志格式**（生产环境）：

```json
{
  "timestamp": "2026-08-09T14:30:00.123+08:00",
  "level": "INFO",
  "logger": "com.ohkb.core.rag.RagPipelineImpl",
  "thread": "http-nio-8080-exec-5",
  "traceId": "a1b2c3d4e5f6",
  "spanId": "rag-retrieve",
  "userId": "user-12345",
  "channel": "web",
  "module": "rag",
  "message": "RAG Pipeline completed",
  "metrics": {
    "question": "怎么提交申报？",
    "category": "howto",
    "retrievedDocs": 5,
    "confidence": 0.82,
    "cachedHit": false,
    "latencyMs": 1250,
    "promptTokens": 3200,
    "cachedTokens": 1680,
    "completionTokens": 180
  },
  "exception": null
}
```

**50. MDC 全链路追踪**

每个请求进入时（`javax.servlet.Filter` 或 Spring `HandlerInterceptor`），自动生成 `traceId`（UUID 前 12 位）并注入 `MDC`：

```java
@Component
public class TraceInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String traceId = Optional.ofNullable(request.getHeader("X-Trace-Id"))
                .orElse(UUID.randomUUID().toString().substring(0, 12));
        MDC.put("traceId", traceId);
        MDC.put("userId", extractUserId(request));
        MDC.put("channel", extractChannel(request));
        response.setHeader("X-Trace-Id", traceId);
        return true;
    }

    @Override
    public void afterCompletion(/* ... */) {
        MDC.clear(); // 防止线程池复用导致的上下文污染
    }
}
```

**51. 关键业务日志埋点**

以下事件强制 INFO 日志 + 结构化 `metrics` 字段：

| 事件 | 日志内容 | 用途 |
|------|---------|------|
| RAG Pipeline 完成 | traceId, question 摘要, category, confidence, latency, tokens | 回答质量分析、成本核算 |
| LLM 调用失败 | traceId, 失败次数, 错误详情, 是否触发降级 | 可用性监控 |
| 语义缓存命中/未命中 | traceId, similarity, threshold, 是否跳过 LLM | 缓存效果分析 |
| 企微消息处理 | traceId, groupId, intent, 是否自动回复, latency | 企微通道质量 |
| 工单状态变更 | traceId, ticketId, from→to, operator | 客服工作审计 |
| 知识条目变更 | traceId, articleId, operation(新增/修改/删除) | 知识库变更审计 |
| 文档导入完成 | traceId, documentId, totalChunks, failedChunks, latency | 文档处理监控 |
| 反馈分类 | traceId, messageId, feedbackCategory, 原始反馈文本 | 知识盲区分析 |
| 限流触发 | traceId, userId/groupId, limitType, 触发计数值 | 滥用监控 |
| 安全事件 | traceId, 敏感词命中, 用户信息, 被过滤的问题 | 安全审计 |

**52. 日志脱敏**

日志中不得出现明文敏感信息：API Key（截取前 8 位 + `...`）、用户手机号（`138****5678`）、身份证号（`320***199001011234`）、JWT Token（完全隐藏）。

Logback 自定义 `MessageConverter` 或 Logback `CompositeConverter` 实现正则脱敏：

```java
// 原型：自定义 Converter 对日志消息做正则脱敏
public class SensitiveDataConverter extends MessageConverter {
    // 手机号: 1[3-9]\d{9} → 1**\****\d{4}
    // API Key: sk-[a-zA-Z0-9]{20,} → sk-****...{后4位}
    // 身份证: \d{17}[\dXx] → \d{3}***\d{4}**\d{4}
}
```

**53. Java 21 特性利用**

- **Virtual Threads**：`spring.threads.virtual.enabled=true`，Tomcat 请求线程和 `@Async` 任务全部使用虚拟线程。企微群高并发（1000+ 群消息/秒）场景下避免平台线程耗尽，且异常堆栈自动包含 carrier thread 信息便于排查。
- **Pattern Matching**：异常处理中 `switch (e)` 按异常类型分发处理逻辑，减少 instanceof 链。
- **Record**：DTO、ErrorResponse、RagResult 等数据载体全部使用 Record 类型，不可变且自带 equals/hashCode/toString。

## Testing Decisions

### 测试原则

- 只测试外部可观测行为，不测试内部实现细节
- 优先测核心切口（RagPipeline），这是所有通道的汇聚点
- 使用真实或接近真实的文档作为测试数据（含复杂表格、多级标题的 Word）
- Mock 外部依赖（百炼 LLM API、Embedding API、企微 API），保证测试速度和确定性

### 测试模块与层次

**46. RagPipeline 集成测试（最高优先级）**——`@SpringBootTest`

- 文档分块质量：结构感知分块在复杂表格、多级嵌套标题下的语义完整性
- 检索准确性：预置 20 条已知文档，10 个标准问题验证 `PgVectorRetriever` Top-5 召回
- 回答质量：30 组 golden dataset Q&A 对，验证语义一致性和引用正确性
- Confidence 评分：验证组合公式的区分度
- 引用校验：`CitationVerifier` 正确识别 YES/NO/PARTIALLY
- Token 预算：摘要压缩触发时机和压缩后上下文质量
- 边界条件：空知识库、超大文档（>100 页）、`chunk_status = 'parse_failed'` 场景

**47. 企微 Webhook 契约测试**——`@WebMvcTest(WechatCallbackController.class)`

- 消息格式解析（XML/JSON 格式的文本、图片、混合消息）
- 多信号意图识别：回复链检测、用户窗口路由、二分类模型
- 消息去重（`MsgId` 幂等）
- 限流保护（Guava RateLimiter 行为）
- 自动降级触发与恢复（模拟连续误触发）
- 回调 URL 验证（`echostr` 解密）

**48. 知识库管理 API 测试**——`@WebMvcTest(KbController.class)`

- 文档上传 → 解析 → 分块 → 嵌入全流程状态转换
- 部分成功策略：模拟文档中某片段解析失败，验证其余正常入库
- 知识条目 CRUD + 语义缓存精准失效
- 批量导入 Excel 格式校验
- 版本同步：推送变更 → 条目标记 deprecated

**49. 语义缓存测试**——`@SpringBootTest`

- 双层阈值命中/未命中验证
- 缓存失效：`article` 更新后 `article_ids` 精准失效
- 冷却消息不计入缓存

**50. 问答质量回归测试**

- 每次知识库版本变更后，自动运行 golden dataset 验证
- 检测回答质量退化（语义相似度下降 >10% 则告警）
- 新增知识条目后验证相关问题命中

**51. E2E 烟雾测试**——Selenium / Playwright

- Web 端：JWT 鉴权 → 打开聊天窗口 → 输入问题 → 流式 SSE 回答 → 引用校验 → 点赞/点踩
- 企微端：模拟群消息 POST → 多信号意图识别 → Bot 回复 → 转人工 → 工单创建
- 管理后台：登录 → 上传文档 → 等待解析完成 → 查看分块预览 → 手动创建 Q&A

**52. 工单系统测试**

- 工单创建 → `ApplicationEvent` 发布 → 通知推送
- 工单状态流转：PENDING → CLAIMED → RESOLVED
- `@Async` 纳入知识库：异步创建草稿 → 不阻塞关闭

## Out of Scope

以下功能不在本次 PRD 范围内，留待后续迭代：

- **语音/图片问答**：仅支持文本输入，不支持语音转文字或图片内容识别提问
- **多语言支持**：初期仅支持中文问答
- **自定义模型微调**：使用预训练模型 + RAG，不进行领域模型微调
- **Agent 自主执行操作**：AI 只回答问题，不代替用户在 SaaS 系统中执行操作
- **移动端原生 App**：React SDK 聊天组件响应式适配移动浏览器即可
- **视频内容解析**：培训视频的文字转录和检索不在范围内
- **SaaS 系统数据库直读**：知识库内容需人工导入或文档同步
- **多租户知识库隔离 v1**：初期一个知识库服务所有客户
- **自定义问答风格/人设**：AI 角色语气固定为专业客服风格，暂不支持自定义

## Further Notes

**53. 前置技术验证**

进入正式开发前，必须完成以下技术验证（已于 v1.1 完成）：

- ✅ 百炼 Prompt Caching 支持（隐式 + 显式，命中率 98.9%）
- ✅ 百炼 Embedding API QPS 限制和延迟
- ⬜ 企微群 Bot API 实际频率限制和消息格式验证
- ⬜ 结构感知分块在真实 Word 文档（含合并单元格表格）上的效果验证

**54. 渐进式上线策略**

- Phase 1：Web 端 AI 问答 + 知识库管理（对内灰度），验证 RAG 质量和 confidence 阈值
- Phase 2：引入语义缓存 + 反馈闭环，优化成本和回答质量
- Phase 3：接入企业微信外部群（先用 `at_only` 模式，积累数据后开启 `smart_detect`）
- Phase 4：开放工单系统和人机协同完整流程

**55. 知识库冷启动**

建议开发期间同步整理现有文档——至少准备 50 条高频 Q&A + 完整系统操作手册 PDF + 20+ 份真实复杂文档作为解析测试集。

**56. 成本控制**

- Prompt Caching：显式 `cache_control` 标记，缓存命中部分成本降低 90%（已验证 ✅）
- 语义缓存：双层阈值设计预计减少 40-60% LLM 调用
- Token 预算管理：6000 tokens/请求硬上限
- 企微意图识别：非问题类消息用规则匹配，不消耗 LLM Token

**57. 企业微信限制**

- 企微 Bot API 群消息频率约 20 条/分钟/群
- 高峰期消息队列排队，超时降级
- 限流触发时自动切换为 `at_only` 模式

**58. 持续学习闭环**

核心飞轮：客户提问 → AI 回答 → 点踩自动分类归因 → 管理员按原因分组审查 → 知识补充/修正 → 回答质量提升 → 客户满意度提升 → 提问量增长。上线后应关注飞轮健康度指标，而非单纯看问答数量。
