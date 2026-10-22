-- 消息已读回执水位线表。
-- ⚠️ ddl-auto: update 能自动建表，但唯一约束在存量库上不保证跟着建（Hibernate 对已存在表的
-- 约束变更处理不一致）。本脚本幂等，可重复执行。
-- 缺唯一约束的后果：同一 (会话, 读者) 会积累多行水位线，读出来哪行看运气，且不报错。
--
-- 一次性手动执行（项目未接入 Flyway/Liquibase）：
--   docker exec -i qingyu-postgres psql -U postgres -d qingyu_care \
--     < backend/src/main/resources/migration/2026-08-20-add-message-read-receipt.sql

CREATE TABLE IF NOT EXISTS message_read_receipt (
    id               BIGSERIAL PRIMARY KEY,
    session_id       BIGINT       NOT NULL,
    reader_type      VARCHAR(20)  NOT NULL,
    reader_id        BIGINT       NOT NULL,
    up_to_message_id BIGINT       NOT NULL,
    read_at          TIMESTAMP    NOT NULL,
    tenant_id        BIGINT,
    app_id           BIGINT
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_receipt_session_reader
    ON message_read_receipt (session_id, reader_type, reader_id);

-- 未读数聚合按 (session_id, reader_type) 查回执，建一条覆盖索引
CREATE INDEX IF NOT EXISTS idx_receipt_session_type
    ON message_read_receipt (session_id, reader_type);
