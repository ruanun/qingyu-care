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
                .withProperty("qingyu.care.platform.admin-initial-password", "change-me-in-production")
                .withProperty("spring.data.redis.password", "Change-Me-Too")
                .withProperty("spring.datasource.password", "a-real-random-value");

        assertThatThrownBy(() -> new ProductionSecretsGuard(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QINGYU_ADMIN_INITIAL_PASSWORD")
                .hasMessageContaining("REDIS_PASSWORD")
                .hasMessageNotContaining("SPRING_DATASOURCE_PASSWORD");
    }

    /** demo 是对外演示环境，照样要拦——只有 dev/test 豁免 */
    @Test
    void demo_profile同样要拦() {
        MockEnvironment env = new MockEnvironment().withProperty("dromara.x-file-storage.amazon-s3-v2[0].secret-key", "change-me-in-production");
        env.setActiveProfiles("demo");
        assertThatThrownBy(() -> new ProductionSecretsGuard(env).afterPropertiesSet())
                .hasMessageContaining("STORAGE_SECRET_KEY");
    }

    @Test
    void dev与test_profile豁免() {
        for (String profile : new String[]{"dev", "test"}) {
            MockEnvironment env = new MockEnvironment().withProperty("spring.data.redis.password", "change-me-in-production");
            env.setActiveProfiles(profile);
            assertThatCode(() -> new ProductionSecretsGuard(env).afterPropertiesSet()).as(profile).doesNotThrowAnyException();
        }
    }

    /** 本地不带 profile 直接跑、变量都没设时不能误伤 */
    @Test
    void 变量未设置或已是真实值时放行() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.data.redis.password", "k8Jq2mX9vLp4");
        assertThatCode(() -> new ProductionSecretsGuard(env).afterPropertiesSet()).doesNotThrowAnyException();
    }

    /**
     * 2026-09-30 安全审查：不用 compose、直接跑 jar 时环境变量没设，application.yml 里的默认值照样生效，
     * 只查环境变量的版本看到 null 就放行了。按实际生效的属性值再查一遍。
     */
    @Test
    void 环境变量没设但生效配置仍是仓库默认值时拒绝启动() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.password", "postgres")
                .withProperty("dromara.x-file-storage.amazon-s3-v2[0].secret-key", "rustfsadmin123")
                .withProperty("spring.data.redis.password", "");

        assertThatThrownBy(() -> new ProductionSecretsGuard(env).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_DATASOURCE_PASSWORD")
                .hasMessageContaining("STORAGE_SECRET_KEY")
                .hasMessageNotContaining("REDIS_PASSWORD");
    }

    @Test
    void 生效配置是真实随机值时放行() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.password", "Xk2!9fQz-real")
                .withProperty("dromara.x-file-storage.amazon-s3-v2[0].secret-key", "s3-real-secret-77");

        new ProductionSecretsGuard(env).afterPropertiesSet();
    }
}
