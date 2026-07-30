-- ============================================================
-- 存量数据修复：Boot 4.1 + Spring AI 2.0 迁移导致的 baseUrl 契约翻转
--
-- 背景：Spring AI 1.x 的 OpenAiApi 会自动往 baseUrl 后拼 /v1/chat/completions、
-- /v1/embeddings；2.0 起改用官方 openai-java SDK，只拼 chat/completions、
-- embeddings，不再补 /v1——baseUrl 必须自带 /v1。这次迁移已把三处代码侧的默认值
-- （application.yml 的 base-url、ModelRouterService 的 @Value 兜底、
-- getDefaultEndpoint() 四个分支）都补齐了 /v1，但代码改不到第四处：
-- ai_model_config 表里已经存在的行——平台默认模型行只在表为空时由
-- ModelRouterService.init() 播种，任何在本次迁移之前跑过的库（dev/staging/prod）
-- 这行早已存在，endpoint 是旧的不带 /v1 的值（如
-- https://dashscope.aliyuncs.com/compatible-mode），新默认值对它完全无效。
--
-- 不执行的后果：本次迁移合并部署到存量库后，凡是走这类 endpoint 的模型配置，
-- 对话/摄取（embedding）/检索全线打成 POST .../chat/completions（缺 /v1）
-- → 100% 404。租户自建的、同样缺 /v1 的 OpenAI 兼容端点行也会中招。
--
-- 幂等性：只处理"去掉末尾斜杠后不以 /v1 结尾"的行；执行后这些行均以 /v1 结尾，
-- 重复执行不会再匹配、不会变成 .../v1/v1。
--
-- 已知局限（非阻塞，人工过一眼时留意）：
--   - 按字符串后缀 LIKE '%/v1' 匹配，大小写敏感（如 .../V1 会被误判为缺失并重复追加）；
--   - 若存在端点路径恰好以 "v10" "v11" 等结尾（几乎不可能出现在真实 OpenAI 兼容端点上），
--     后缀匹配同样会误判为缺失。
-- 本仓当前 ai_model_config 行数是个位数，建议执行前先人工核对：
--   SELECT id, name, endpoint FROM ai_model_config;
-- 确认待更新的行确实是"OpenAI 兼容端点缺 /v1"，而非租户自建的、本就不该加 /v1 的端点
-- （比如非 OpenAI 兼容协议的自定义网关）。
--
-- 一次性手动执行（项目未接入 Flyway/Liquibase）：
--   docker exec -i qingyu-postgres psql -U postgres -d qingyu_care \
--     < backend/src/main/resources/migration/2026-07-30-append-v1-to-model-endpoint.sql
-- ============================================================

UPDATE ai_model_config
SET endpoint = rtrim(endpoint, '/') || '/v1'
WHERE endpoint IS NOT NULL
  AND endpoint <> ''
  AND rtrim(endpoint, '/') NOT LIKE '%/v1';
