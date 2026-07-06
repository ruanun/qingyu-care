package com.qingyu.care.it;

import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证自定义镜像的 zhparser 扩展 + chinese_zh 文本搜索配置可用（中文能分词、能匹配）。 */
class ZhparserSmokeIT extends AbstractPostgresIT {

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void chinese_zh配置存在且能匹配中文() {
        Boolean matched = jdbcTemplate.queryForObject(
                "SELECT to_tsvector('chinese_zh', '轻语智能客服支持产品型号XZ-9000') " +
                        "@@ plainto_tsquery('chinese_zh', '产品型号')",
                Boolean.class);
        assertThat(matched).isTrue();
    }
}
