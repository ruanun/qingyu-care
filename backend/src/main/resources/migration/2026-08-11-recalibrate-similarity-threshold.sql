-- ============================================================
-- 存量数据修复：平台默认问答策略的相似度阈值重标定
--
-- 背景：平台默认策略的 similarityThreshold 是 2026-07-03 首次播种时定的 0.7，
-- 当时的 embedding 模型与现在（qwen3.7-text-embedding）不同。2026-08-11 在真机上
-- 实测当前模型的分数分布（中文短问句 → 帮助中心长段落）：
--
--   真正相关的命中  0.57 ~ 0.73
--     · 「七天无理由退货」（逐字命中文档标题）  0.7298
--     · 「多少钱包邮？偏远地区呢？」            0.6365
--     · 「积分有效期是多久」                    0.6092
--     · 「订单满99元即可包邮吗」                0.5709
--   完全无关的问题  0.25 ~ 0.30
--     · 「今天北京天气怎么样」                  0.2969
--     · 「Python 怎么读取 CSV 文件」            0.2905
--     · 「帮我写一首关于春天的诗」              0.2479
--
-- 0.7 恰好卡在「相关命中」那一带的顶端，只有逐字命中标题的问法才够得着，
-- 其余全被滤掉。0.45 落在 0.30 与 0.57 之间那段干净空档的中央。
--
-- 不执行的后果：代码侧的默认值只在表为空时由 ChatStrategyService.init() 播种，
-- 任何在本次改动之前跑过的库（dev/staging/prod）这两行早已存在，新默认值对它们
-- 完全无效。表现为「知识库明明有内容、问什么都答不知道」，且全程无异常无日志——
-- 检索返回空是 fail-soft 的正常路径，只会走到兜底话术。
--
-- complaint 场景同步从 0.8 调到 0.55，保持「比默认严一档」（+0.1）的原有意图；
-- 照搬 0.8 到新刻度会变成「任何问题都够不着、永远转人工」。
--
-- 幂等性：只更新仍是旧值的平台默认行（tenant_id IS NULL）。重复执行第二次匹配 0 行。
-- 租户自建的策略行不动——那些是租户自己调过的值，不该被平台迁移覆盖。
--
-- ⚠️ 换 embedding 模型后这个刻度会整体平移，必须重新测量而不是沿用本值。
--    测法：把阈值临时调到 0.05，问几个「确定该命中」和几个「确定不该命中」的问题，
--    查 chat_message.confidence_score 看两组分数各自落在哪个区间，取中间的空档。
--
-- 一次性手动执行（项目未接入 Flyway/Liquibase）：
--   docker exec -i qingyu-postgres psql -U postgres -d qingyu_care \
--     < backend/src/main/resources/migration/2026-08-11-recalibrate-similarity-threshold.sql
--
-- 执行前建议先看一眼将被影响的行：
--   SELECT id, name, scenario, similarity_threshold, tenant_id
--   FROM chat_strategy WHERE tenant_id IS NULL;
-- ============================================================

UPDATE chat_strategy
SET similarity_threshold = 0.45
WHERE tenant_id IS NULL
  AND scenario = 'default'
  AND similarity_threshold = 0.7;

UPDATE chat_strategy
SET similarity_threshold = 0.55
WHERE tenant_id IS NULL
  AND scenario = 'complaint'
  AND similarity_threshold = 0.8;
