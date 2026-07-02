-- ============================================================
-- 轻语 (QingYu Care) 数据库结构参考
-- PostgreSQL 17 + pgvector 0.8+
-- ============================================================
-- ⚠️ 重要说明：
--   本文件仅作为「表结构参考文档」，用于快速了解数据模型。
--   实际建表由 JPA / Hibernate 的 ddl-auto: update 自动完成，
--   本脚本【不会】被应用启动时执行（application.yml 未配置
--   spring.sql.init）。请勿手动执行本脚本，以免与 Hibernate
--   自动维护的结构产生冲突。
--
--   唯一需要预先执行的初始化是启用 pgvector 扩展，该步骤已由
--   docker/init.sql 在容器首次启动时完成。
--
--   命名规则：实体驼峰字段由 Hibernate 默认策略转为下划线，
--   例如 contactName -> contact_name。
-- ============================================================

-- 启用 pgvector 扩展（由 docker/init.sql 自动执行）
CREATE EXTENSION IF NOT EXISTS vector;

-- ============================================================
-- 一、平台 / 多租户体系
-- ============================================================

-- 租户表（企业客户）
CREATE TABLE IF NOT EXISTS sys_tenant (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(50) NOT NULL UNIQUE,            -- 租户编码（唯一标识）
    contact_name VARCHAR(50),
    contact_phone VARCHAR(20),
    contact_email VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE / FROZEN / EXPIRED
    plan VARCHAR(20) NOT NULL DEFAULT 'FREE',     -- FREE / PRO / ENTERPRISE
    expire_time TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 应用表（机器人实例，隶属租户）
CREATE TABLE IF NOT EXISTS sys_application (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES sys_tenant(id),
    name VARCHAR(100) NOT NULL,
    app_key VARCHAR(64) NOT NULL UNIQUE,          -- 用于 API 调用
    app_secret VARCHAR(128) NOT NULL,
    description TEXT,
    avatar VARCHAR(255),
    welcome_message TEXT,
    theme_color VARCHAR(20) DEFAULT '#6366f1',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 用户表（tenant_id 为 NULL 表示平台管理员）
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT REFERENCES sys_tenant(id),
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(128) NOT NULL,               -- 加密存储
    nickname VARCHAR(50),
    avatar VARCHAR(255),
    email VARCHAR(100),
    phone VARCHAR(20),
    role VARCHAR(20) NOT NULL,                     -- PLATFORM_ADMIN / TENANT_ADMIN / TENANT_MEMBER
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_time TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 二、RAG 知识库
-- 说明：以下业务表均继承 BaseTenantEntity，含 tenant_id + app_id
--       两级隔离字段，由 Hibernate Filter + AOP 自动过滤。
-- ============================================================

-- 知识库表
CREATE TABLE IF NOT EXISTS knowledge_base (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    tenant_id BIGINT,
    app_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 文档表
CREATE TABLE IF NOT EXISTS document (
    id BIGSERIAL PRIMARY KEY,
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255),
    document_type VARCHAR(20) NOT NULL,            -- PDF / WORD / EXCEL / PPT / HTML / TEXT ...
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING / PROCESSING / COMPLETED / FAILED
    file_path VARCHAR(500),
    file_size BIGINT,
    storage_key VARCHAR(500),                      -- S3 / RustFS 文件键
    content TEXT,
    chunk_count INTEGER DEFAULT 0,
    error_message TEXT,
    tenant_id BIGINT,
    app_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 向量存储表（由 Spring AI PgVector Starter 自动创建，此处仅供参考）
-- CREATE TABLE IF NOT EXISTS vector_store (
--     id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
--     content TEXT,
--     metadata JSONB,                             -- 含 tenant/app/knowledgeBase 等隔离信息
--     embedding vector(1024)                      -- text-embedding-v3 维度，见 application.yml
-- );

-- ============================================================
-- 三、会话与消息
-- ============================================================

-- 会话表
CREATE TABLE IF NOT EXISTS chat_session (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(64),
    status VARCHAR(20) NOT NULL DEFAULT 'AI_PROCESSING', -- AI_PROCESSING / WAITING_AGENT / AGENT_PROCESSING / CLOSED ...
    summary VARCHAR(500),                          -- AI 生成的会话摘要
    agent_id BIGINT,                               -- 人工客服 ID
    handover_time TIMESTAMP,
    tenant_id BIGINT,
    app_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMP
);

-- 会话标签表（多标签，@ElementCollection）
CREATE TABLE IF NOT EXISTS chat_session_tags (
    session_id BIGINT NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    tag VARCHAR(30)
);

-- 消息表（不做租户隔离，通过所属会话间接归属）
CREATE TABLE IF NOT EXISTS chat_message (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,                     -- USER / ASSISTANT / SYSTEM / AGENT
    content TEXT NOT NULL,
    hit_chunk_ids VARCHAR(500),                    -- 命中的知识片段 IDs（JSON 数组）
    confidence_score DOUBLE PRECISION,
    token_usage INTEGER,
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 四、问答策略 / Prompt / 模型 / 转人工
-- ============================================================

-- 问答策略表（动态控制 RAG 参数）
CREATE TABLE IF NOT EXISTS chat_strategy (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    scenario VARCHAR(50) NOT NULL,                 -- default / complaint / faq ...
    top_k INTEGER NOT NULL DEFAULT 5,
    similarity_threshold DOUBLE PRECISION NOT NULL DEFAULT 0.7,
    allow_no_knowledge BOOLEAN NOT NULL DEFAULT TRUE,
    fallback_message VARCHAR(500),
    prompt_template_id BIGINT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    tenant_id BIGINT,
    app_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Prompt 模板表（支持版本管理）
CREATE TABLE IF NOT EXISTS prompt_templates (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    version INTEGER NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,      -- 同一时间仅一个激活版本
    description VARCHAR(500),
    tenant_id BIGINT,
    app_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- AI 模型配置表（多模型路由与降级）
CREATE TABLE IF NOT EXISTS ai_model_config (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    provider VARCHAR(50) NOT NULL,                 -- openai / dashscope / zhipu / moonshot / deepseek ...
    model_id VARCHAR(100) NOT NULL,                -- qwen-max / gpt-4o-mini / glm-4 ...
    endpoint VARCHAR(500),
    api_key VARCHAR(500),                          -- 生产环境应加密存储
    scenario VARCHAR(50) NOT NULL DEFAULT 'default',
    priority INTEGER NOT NULL DEFAULT 100,         -- 数字越小优先级越高（用于降级）
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    remark VARCHAR(500),
    tenant_id BIGINT,
    app_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 转人工规则表
CREATE TABLE IF NOT EXISTS handover_rules (
    id BIGSERIAL PRIMARY KEY,
    rule_type VARCHAR(30) NOT NULL,                -- KEYWORD / LOW_CONFIDENCE / NO_HIT_STREAK ...
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    config TEXT,                                   -- JSON 配置，如 {"keywords": ["转人工"]}
    priority INTEGER NOT NULL DEFAULT 100,
    tenant_id BIGINT,
    app_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 五、索引参考
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_application_tenant   ON sys_application(tenant_id);
CREATE INDEX IF NOT EXISTS idx_user_tenant          ON sys_user(tenant_id);
CREATE INDEX IF NOT EXISTS idx_kb_tenant_app        ON knowledge_base(tenant_id, app_id);
CREATE INDEX IF NOT EXISTS idx_document_kb_id       ON document(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_document_status      ON document(status);
CREATE INDEX IF NOT EXISTS idx_session_user_id      ON chat_session(user_id);
CREATE INDEX IF NOT EXISTS idx_session_status       ON chat_session(status);
CREATE INDEX IF NOT EXISTS idx_session_tenant_app   ON chat_session(tenant_id, app_id);
CREATE INDEX IF NOT EXISTS idx_message_session_id   ON chat_message(session_id);
