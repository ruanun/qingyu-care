-- ============================================================
-- 历史数据迁移：将 app_id IS NULL 的业务数据绑定到每个租户的"默认应用"
-- 默认应用 = 该租户下 id 最小（最早创建）的应用
-- 幂等：仅更新 app_id IS NULL 的行；tenant_id 为空或该租户无应用时跳过
-- 一次性手动执行（项目未接入 Flyway/Liquibase）
--
-- 执行：
--   docker exec -i qingyu-postgres psql -U postgres -d qingyu_care \
--     < backend/src/main/resources/migration/2026-07-01-bind-legacy-app-id.sql
-- ============================================================

DO $$
DECLARE
    tbl TEXT;
    tables TEXT[] := ARRAY[
        'knowledge_base', 'document', 'chat_session',
        'chat_strategy', 'handover_rules', 'prompt_templates', 'ai_model_config'
    ];
BEGIN
    FOREACH tbl IN ARRAY tables LOOP
        EXECUTE format($f$
            UPDATE %I t
            SET app_id = (
                SELECT MIN(a.id) FROM sys_application a WHERE a.tenant_id = t.tenant_id
            )
            WHERE t.app_id IS NULL
              AND t.tenant_id IS NOT NULL
              AND EXISTS (SELECT 1 FROM sys_application a WHERE a.tenant_id = t.tenant_id)
        $f$, tbl);
        RAISE NOTICE '已处理表: %', tbl;
    END LOOP;
END $$;
