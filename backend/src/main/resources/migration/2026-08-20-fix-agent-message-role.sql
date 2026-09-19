-- 把存量坐席消息的 role 从 ASSISTANT 修正为 AGENT。
--
-- 判据 confidence_score IS NULL 是精确的、不是启发式：写入 role='ASSISTANT' 的路径
-- 只有三条，且已逐一核实——
--   ChatServiceImpl.saveMessages（AI 回复）：参数是基本类型 double，语法上不可能为 null
--   DemoDataSeeder（演示数据）：显式 .confidenceScore(confidence)
--   ChatServiceImpl.agentReply（坐席回复）：从不设置 → null
--
-- 幂等：改完之后这些行的 role 已是 AGENT，WHERE 不再命中，可重复执行。
--
-- ⚠️ 执行前先单独跑一次下面的 ① 人工核对内容确实是坐席说的话。脚本里保留 ① 是为了
-- 让执行时的输出成为审计记录。
--
-- 一次性手动执行（项目未接入 Flyway/Liquibase）：
--   docker exec -i qingyu-postgres psql -U postgres -d qingyu_care \
--     < backend/src/main/resources/migration/2026-08-20-fix-agent-message-role.sql

-- ① 待修正的行
SELECT id, session_id, created_at, left(content, 40) AS content_preview
FROM chat_message
WHERE role = 'ASSISTANT' AND confidence_score IS NULL
ORDER BY id;

-- ② 修正
UPDATE chat_message
SET role = 'AGENT'
WHERE role = 'ASSISTANT' AND confidence_score IS NULL;
