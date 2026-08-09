-- V2: Dead Letter 表 + 异步任务失败记录
-- 参照 PRD Decision §48：@Async 方法异常不能静默吞掉

CREATE TABLE IF NOT EXISTS dead_letter (
    id BIGSERIAL PRIMARY KEY,
    task_type VARCHAR(50) NOT NULL,          -- document_parsing, embedding_generation, feedback_classify, notification
    task_payload JSONB,                      -- 原始任务参数
    error_message TEXT NOT NULL,
    error_stack TEXT,                        -- 完整堆栈
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 3,
    next_retry_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'retrying', 'failed', 'resolved')),
    resolved_by VARCHAR(100),
    resolution_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dead_letter_status ON dead_letter(status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_dead_letter_type ON dead_letter(task_type, created_at);

-- 自动更新 updated_at 触发器
CREATE OR REPLACE FUNCTION update_dead_letter_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_dead_letter_updated_at ON dead_letter;
CREATE TRIGGER trg_dead_letter_updated_at
    BEFORE UPDATE ON dead_letter
    FOR EACH ROW EXECUTE FUNCTION update_dead_letter_updated_at();
