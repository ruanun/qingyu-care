package com.qingyu.care.config;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.persistence.EntityManager;
import org.hibernate.Filter;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 多租户数据切面的过滤决策单测。
 * 覆盖“未登录鉴权前查询不得被误过滤”（登录 findByUsername 依赖此），
 * 以及“有上下文按上下文过滤”“已登录非管理员无上下文安全拒绝”。
 *
 * @author ruan
 */
class TenantDataAspectTest {

    private final EntityManager entityManager = mock(EntityManager.class);
    private final Session session = mock(Session.class);
    private final Filter filter = mock(Filter.class);
    private final TenantDataAspect aspect = new TenantDataAspect(entityManager);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void stubSession() {
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        when(session.enableFilter(anyString())).thenReturn(filter);
        when(filter.setParameter(anyString(), any())).thenReturn(filter);
    }

    @Test
    void 未登录且无租户上下文时不施加过滤_以免挡住登录查询() {
        stubSession();
        TenantContext.clear();
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(false);

            aspect.enableFilters();
        }
        // 关键：鉴权前（登录/注册/开放 API 查 appKey）绝不能启用 tenantFilter，
        // 否则 findByUsername/findByAppKey 会被 tenant_id=-1 挡掉，导致谁都登录不了。
        verify(session, never()).enableFilter(anyString());
    }

    @Test
    void 有租户上下文时按上下文启用租户过滤() {
        stubSession();
        TenantContext.setTenantId(5L);

        aspect.enableFilters();

        verify(session).enableFilter("tenantFilter");
        verify(filter).setParameter("tenantId", 5L);
    }

    @Test
    void 已登录非管理员却无上下文时安全默认拒绝() {
        stubSession();
        TenantContext.clear();
        SaSession saSession = mock(SaSession.class);
        when(saSession.get("role")).thenReturn("TENANT_ADMIN");
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(StpUtil::getSession).thenReturn(saSession);

            aspect.enableFilters();
        }
        verify(session).enableFilter("tenantFilter");
        verify(filter).setParameter("tenantId", -1L);
    }
}
