-- PostgreSQL 初始化脚本
-- 该脚本在容器首次启动时自动执行

-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 其他初始化 SQL 由 JPA 自动管理
