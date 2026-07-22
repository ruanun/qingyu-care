package com.qingyu.care.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户归属守卫的对称空容忍语义单测。
 * 复刻 86f6892（ChatFeedbackService）/ 3469510（AbTestService）已验证的比对规则，
 * 供各服务按 id 加载（findById，不受 Hibernate @Filter 保护）后统一复用。
 *
 * @author ruan
 */
class TenantOwnershipGuardTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void 同租户_放行() {
        TenantContext.setTenantId(1L);
        assertThat(TenantOwnershipGuard.belongsToCurrentContext(1L, null)).isTrue();
    }

    @Test
    void 他租户_拒绝() {
        TenantContext.setTenantId(1L);
        assertThat(TenantOwnershipGuard.belongsToCurrentContext(2L, null)).isFalse();
    }

    @Test
    void 平台管理员上下文为空_放行任意租户资源() {
        // 平台管理员登录后 TenantContext.tenantId 恒为 null，应对任意资源放行
        assertThat(TenantOwnershipGuard.belongsToCurrentContext(2L, 3L)).isTrue();
    }

    @Test
    void 平台共享资源租户为空_放行() {
        // tenant_id IS NULL 的平台默认配置，租户可见（多级回退语义）
        TenantContext.setTenantId(1L);
        assertThat(TenantOwnershipGuard.belongsToCurrentContext(null, null)).isTrue();
    }

    @Test
    void 同租户不同应用_拒绝() {
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(10L);
        assertThat(TenantOwnershipGuard.belongsToCurrentContext(1L, 20L)).isFalse();
    }

    @Test
    void 同租户同应用_放行() {
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(10L);
        assertThat(TenantOwnershipGuard.belongsToCurrentContext(1L, 10L)).isTrue();
    }

    @Test
    void 应用上下文为空_放行任意应用资源() {
        TenantContext.setTenantId(1L);
        assertThat(TenantOwnershipGuard.belongsToCurrentContext(1L, 20L)).isTrue();
    }
}
