package com.qingyu.care.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 占位符密钥拒绝启动（2026-09-25 安全扫描 M4）。
 *
 * @author ruan
 */
class ProductionSecretsGuardTest {

    @Test
    void 生产环境仍用占位符时拒绝启动并列出变量名() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("QINGYU_ADMIN_INITIAL_PASSWORD", "change-me-in-production")
                .withProperty("REDIS_PASSWORD", "Change-Me-Too")
                .withProperty("SPRING_DATASOURCE_PASSWORD", "a-real-random-value");

        assertThatThrownBy(() -> new ProductionSecretsGuard(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QINGYU_ADMIN_INITIAL_PASSWORD")
                .hasMessageContaining("REDIS_PASSWORD")
                .hasMessageNotContaining("SPRING_DATASOURCE_PASSWORD");
    }

    /** demo 是对外演示环境，照样要拦——只有 dev/test 豁免 */
    @Test
    void demo_profile同样要拦() {
        MockEnvironment env = new MockEnvironment().withProperty("STORAGE_SECRET_KEY", "change-me-in-production");
        env.setActiveProfiles("demo");
        assertThatThrownBy(() -> new ProductionSecretsGuard(env).afterPropertiesSet())
                .hasMessageContaining("STORAGE_SECRET_KEY");
    }

    @Test
    void dev与test_profile豁免() {
        for (String profile : new String[]{"dev", "test"}) {
            MockEnvironment env = new MockEnvironment().withProperty("REDIS_PASSWORD", "change-me-in-production");
            env.setActiveProfiles(profile);
            assertThatCode(() -> new ProductionSecretsGuard(env).afterPropertiesSet()).as(profile).doesNotThrowAnyException();
        }
    }

    /** 本地不带 profile 直接跑、变量都没设时不能误伤 */
    @Test
    void 变量未设置或已是真实值时放行() {
        MockEnvironment env = new MockEnvironment().withProperty("REDIS_PASSWORD", "k8Jq2mX9vLp4");
        assertThatCode(() -> new ProductionSecretsGuard(env).afterPropertiesSet()).doesNotThrowAnyException();
    }
}
