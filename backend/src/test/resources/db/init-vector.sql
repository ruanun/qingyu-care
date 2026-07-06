CREATE EXTENSION IF NOT EXISTS vector;

-- 中文全文检索：zhparser 扩展 + chinese_zh 文本搜索配置
CREATE EXTENSION IF NOT EXISTS zhparser;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'chinese_zh') THEN
        CREATE TEXT SEARCH CONFIGURATION chinese_zh (PARSER = zhparser);
        ALTER TEXT SEARCH CONFIGURATION chinese_zh
            ADD MAPPING FOR n,v,a,i,e,l,j WITH simple;
    END IF;
END
$$;
