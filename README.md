# 职业健康检测 SaaS — AI 知识库问答产品

基于 RAG（检索增强生成）的 AI 知识库问答产品，为职业健康检测技术服务全流程 SaaS 系统提供智能化客户支持，并接入企业微信外部客户群实现自动回复。

## 项目概述

本产品服务于已上线的职业健康检测 SaaS 系统，该系统覆盖：

- **项目申报** — 项目立项、信息录入、资质审核
- **现场调查** — 现场勘查、危害因素识别、调查记录
- **检测采样** — 采样计划、检测任务分配、样本管理
- **评价报告编制** — 数据汇总、报告生成、多级审核
- **项目公示** — 结果公开、备案归档

客户在日常使用中频繁遇到操作问题，传统人工客服无法满足 7×24 响应需求。本产品通过构建知识库 + AI 问答 + 企业微信集成，实现智能化的客户支持闭环。

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.x + Spring MVC（Tomcat） |
| AI 框架 | LangChain4j（RAG Pipeline、文档加载、ChatMemory） |
| LLM | 阿里云百炼（DashScope）+ 显式 Prompt Caching |
| Embedding | 百炼 text-embedding-v3（1536 维） |
| 向量数据库 | PostgreSQL 15+ + pgvector（HNSW 索引） |
| 数据库 | PostgreSQL（JDBC） |
| 前端-管理后台 | Thymeleaf + HTMX + Tailwind CSS |
| 前端-聊天组件 | React 19 + Vite（独立 SDK） |
| 文档解析 | Apache Tika + LangChain4j DocumentLoader |
| 企业微信 | 企微 Bot API + 回调 Webhook |
| 运行时 | Java 21（Virtual Threads） |
| 构建工具 | Maven 3.9+ |
| 异常处理 | `@ControllerAdvice` + 三层异常体系 + GlobalErrorCode |
| 日志 | SLF4J + Logback（JSON 结构化 + MDC 全链路追踪 + 脱敏） |

## 项目结构

```
occupational-health-kb-qa/
├── pom.xml                    # Maven 父 POM
├── kb-admin/                  # 管理后台（Thymeleaf + HTMX）
├── kb-chat-sdk/               # 嵌入式聊天组件（React + Vite）
├── kb-core/                   # 核心业务逻辑（RAG、知识库、对话、工单、企微、分析）
├── kb-infrastructure/         # 基础设施（LLM、pgvector、文档解析、缓存、安全）
├── kb-api/                    # REST API（供 React SDK 消费）
└── kb-boot/                   # Spring Boot 启动模块
```

## 快速开始

```bash
# 启动后端
cd kb-boot
mvn spring-boot:run

# 启动聊天组件开发服务器
cd kb-chat-sdk
npm install && npm run dev
```

## 文档

- [PRD.md](./PRD.md) — 完整产品需求文档（v2.0）
