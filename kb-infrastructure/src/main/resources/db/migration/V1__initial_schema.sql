-- ============================================================
-- OHKB v0.1.0 — 初始 Schema
-- PostgreSQL 15+ + pgvector
-- ============================================================

-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================
-- 1. 导入文档
-- ============================================================
CREATE TABLE IF NOT EXISTS imported_documents (
    id BIGSERIAL PRIMARY KEY,
    filename TEXT NOT NULL,
    file_type VARCHAR(10) NOT NULL CHECK (file_type IN ('pdf', 'docx', 'md', 'txt', 'xlsx')),
    original_size BIGINT,
    status VARCHAR(20) NOT NULL DEFAULT 'uploading'
        CHECK (status IN ('uploading', 'parsing', 'chunking', 'embedding', 'completed', 'failed')),
    chunk_count INTEGER DEFAULT 0,
    failed_chunk_count INTEGER DEFAULT 0,
    error_details JSONB,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 2. 知识条目
-- ============================================================
CREATE TABLE IF NOT EXISTS knowledge_articles (
    id BIGSERIAL PRIMARY KEY,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    content_json JSONB,
    category VARCHAR(50) NOT NULL
        CHECK (category IN ('project_application', 'field_survey', 'testing_sampling',
                            'evaluation_report', 'project_publication', 'system_settings', 'general')),
    tags TEXT[] DEFAULT '{}',
    source_type VARCHAR(20) NOT NULL
        CHECK (source_type IN ('manual', 'document_import', 'customer_service')),
    source_doc_id BIGINT REFERENCES imported_documents(id) ON DELETE SET NULL,
    chunk_index INTEGER,
    chunk_status VARCHAR(20) NOT NULL DEFAULT 'ok'
        CHECK (chunk_status IN ('ok', 'parse_failed')),
    embedding VECTOR(1536),
    embedding_model VARCHAR(50) NOT NULL DEFAULT 'text-embedding-v3',
    effective_from TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    effective_until TIMESTAMPTZ,
    is_deprecated BOOLEAN NOT NULL DEFAULT FALSE,
    hit_count INTEGER NOT NULL DEFAULT 0,
    helpful_count INTEGER NOT NULL DEFAULT 0,
    unhelpful_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- HNSW 向量索引（余弦相似度）
CREATE INDEX IF NOT EXISTS idx_articles_embedding
    ON knowledge_articles USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);

CREATE INDEX IF NOT EXISTS idx_articles_category ON knowledge_articles(category);
CREATE INDEX IF NOT EXISTS idx_articles_source_doc ON knowledge_articles(source_doc_id);
CREATE INDEX IF NOT EXISTS idx_articles_deprecated ON knowledge_articles(is_deprecated);
CREATE INDEX IF NOT EXISTS idx_articles_tags ON knowledge_articles USING gin(tags);

-- ============================================================
-- 3. 对话
-- ============================================================
CREATE TABLE IF NOT EXISTS conversations (
    id BIGSERIAL PRIMARY KEY,
    channel VARCHAR(20) NOT NULL CHECK (channel IN ('web', 'wechat_group')),
    external_id TEXT,
    user_id TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'transferred_to_human', 'closed')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_conv_channel ON conversations(channel, external_id);
CREATE INDEX IF NOT EXISTS idx_conv_user ON conversations(user_id);

-- ============================================================
-- 4. 消息
-- ============================================================
CREATE TABLE IF NOT EXISTS messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT REFERENCES conversations(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content TEXT NOT NULL,
    citations JSONB,
    confidence DOUBLE PRECISION,
    feedback VARCHAR(20) CHECK (feedback IN ('helpful', 'unhelpful')),
    feedback_category VARCHAR(30)
        CHECK (feedback_category IN ('wrong_answer', 'missing_knowledge', 'hard_to_understand', 'irrelevant', 'other')),
    feedback_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_msg_conv ON messages(conversation_id, created_at);

-- ============================================================
-- 5. 企微群配置
-- ============================================================
CREATE TABLE IF NOT EXISTS wechat_groups (
    id BIGSERIAL PRIMARY KEY,
    group_id TEXT NOT NULL UNIQUE,
    group_name TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    auto_reply_mode VARCHAR(20) NOT NULL DEFAULT 'smart_detect'
        CHECK (auto_reply_mode IN ('at_only', 'smart_detect', 'auto_degraded')),
    degraded_at TIMESTAMPTZ,
    degraded_reason TEXT,
    consecutive_mistriggers INTEGER NOT NULL DEFAULT 0,
    working_hours_only BOOLEAN NOT NULL DEFAULT FALSE,
    working_hours_start TIME,
    working_hours_end TIME,
    sensitive_keywords TEXT[] DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 6. 工单
-- ============================================================
CREATE TABLE IF NOT EXISTS support_tickets (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT REFERENCES conversations(id) ON DELETE SET NULL,
    source_message_id BIGINT REFERENCES messages(id) ON DELETE SET NULL,
    channel VARCHAR(20) NOT NULL CHECK (channel IN ('web', 'wechat_group')),
    status VARCHAR(20) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'claimed', 'resolved')),
    priority VARCHAR(10) NOT NULL DEFAULT 'normal'
        CHECK (priority IN ('low', 'normal', 'high')),
    assigned_to TEXT,
    ai_answer_snapshot TEXT,
    retrieved_articles BIGINT[],
    resolution_note TEXT,
    knowledge_article_created BIGINT,
    claimed_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tickets_status ON support_tickets(status);
CREATE INDEX IF NOT EXISTS idx_tickets_assigned ON support_tickets(assigned_to);

-- ============================================================
-- 7. 语义缓存
-- ============================================================
CREATE TABLE IF NOT EXISTS semantic_cache (
    id BIGSERIAL PRIMARY KEY,
    question_embedding VECTOR(1536),
    question_text TEXT NOT NULL,
    answer_text TEXT NOT NULL,
    citations JSONB,
    article_ids BIGINT[],
    hit_count INTEGER NOT NULL DEFAULT 1,
    last_hit_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_semcache_embedding
    ON semantic_cache USING hnsw (question_embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);

-- ============================================================
-- 8. 群对话上下文（企微用户级线程持久化）
-- ============================================================
CREATE TABLE IF NOT EXISTS conversation_context (
    id BIGSERIAL PRIMARY KEY,
    group_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    thread_id TEXT NOT NULL,
    topic_summary TEXT,
    message_count INTEGER NOT NULL DEFAULT 0,
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(group_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_ctx_group ON conversation_context(group_id);

-- ============================================================
-- 9. 降级状态记录
-- ============================================================
CREATE TABLE IF NOT EXISTS degrade_events (
    id BIGSERIAL PRIMARY KEY,
    level VARCHAR(10) NOT NULL CHECK (level IN ('L0', 'L1', 'L2', 'L3')),
    direction VARCHAR(10) NOT NULL CHECK (direction IN ('ENTER', 'RECOVER')),
    trigger_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 10. 更新触发器（自动更新 updated_at）
-- ============================================================
CREATE OR REPLACE FUNCTION update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 为有 updated_at 字段的表添加触发器
CREATE TRIGGER trg_imported_documents_updated
    BEFORE UPDATE ON imported_documents
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

CREATE TRIGGER trg_knowledge_articles_updated
    BEFORE UPDATE ON knowledge_articles
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

CREATE TRIGGER trg_conversations_updated
    BEFORE UPDATE ON conversations
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

CREATE TRIGGER trg_wechat_groups_updated
    BEFORE UPDATE ON wechat_groups
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

CREATE TRIGGER trg_support_tickets_updated
    BEFORE UPDATE ON support_tickets
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();
