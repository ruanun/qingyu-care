package com.qingyu.care.config;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.annotation.AdminOnlyWrites;
import com.qingyu.care.annotation.ReadOnlyEndpoint;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * {@link AdminOnlyWritesAspect} 的行为。用 {@link AspectJProxyFactory} 织入真实切面——
 * 直接调控制器方法会绕过 AOP，那样的测试对切面零覆盖。
 *
 * @author ruan
 */
class AdminOnlyWritesAspectTest {

    @AdminOnlyWrites
    static class Fixture {
        @GetMapping("/x") public String read() { return "read"; }
        @PostMapping("/x") public String create() { return "create"; }
        @PutMapping("/x") public String update() { return "update"; }
        @DeleteMapping("/x") public String delete() { return "delete"; }
        @PostMapping("/x/status/batch") @ReadOnlyEndpoint public String queryByPost() { return "query"; }
    }

    private static Fixture proxied() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Fixture());
        factory.setProxyTargetClass(true);
        factory.addAspect(new AdminOnlyWritesAspect());
        return factory.getProxy();
    }

    private static MockedStatic<StpUtil> loginAs(UserRole role) {
        SaSession session = mock(SaSession.class);
        lenient().when(session.get("role")).thenReturn(role.name());
        MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::isLogin).thenReturn(true);
        stp.when(StpUtil::getSession).thenReturn(session);
        return stp;
    }

    @Test
    void 坐席调用写端点一律403() {
        Fixture f = proxied();
        try (MockedStatic<StpUtil> ignored = loginAs(UserRole.TENANT_MEMBER)) {
            assertThatThrownBy(f::create).isInstanceOf(BusinessException.class).hasMessageContaining("仅限租户管理员");
            assertThatThrownBy(f::update).isInstanceOf(BusinessException.class);
            assertThatThrownBy(f::delete).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void 坐席仍可调用读端点与只读POST() {
        Fixture f = proxied();
        try (MockedStatic<StpUtil> ignored = loginAs(UserRole.TENANT_MEMBER)) {
            assertThat(f.read()).isEqualTo("read");
            assertThat(f.queryByPost()).isEqualTo("query");
        }
    }

    @Test
    void 租户管理员与平台管理员可调用写端点() {
        Fixture f = proxied();
        for (UserRole role : new UserRole[]{UserRole.TENANT_ADMIN, UserRole.PLATFORM_ADMIN}) {
            try (MockedStatic<StpUtil> ignored = loginAs(role)) {
                assertThat(f.create()).as(role.name()).isEqualTo("create");
                assertThat(f.delete()).as(role.name()).isEqualTo("delete");
            }
        }
    }
}
