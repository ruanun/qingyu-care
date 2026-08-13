package com.qingyu.care.controller;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.UserUpdateRequest;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link UserController#updateUser} 的提权守卫与密码判空回归测试，直调真实控制器。
 *
 * <p>全仓审计发现：此前只有 {@code PartialUpdateSemanticsTest} 提到
 * {@code UserController}，且那是<b>复刻</b>密码分支的映射逻辑，不是真控制器——把第 174 行
 * 改回只判 {@code != null} 不会有任何测试转红。更要紧的是同一方法里的<b>提权守卫</b>（非平台
 * 管理员不能把用户提升为 {@code PLATFORM_ADMIN}）和 {@code enabled}/{@code role} 的管理员
 * 门禁，此前同样零覆盖。本类补齐这些路径，{@code PartialUpdateSemanticsTest} 里那条复刻测试
 * 相应移除（见其类注释）。
 *
 * <p>{@code StpUtil.getSession()} 用 {@code mockStatic}——同包已有先例
 * {@link ChatControllerTest}，另有 {@code config/TenantDataAspectTest}。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    UserRepository userRepository;
    @Mock
    TenantRepository tenantRepository;
    @InjectMocks
    UserController controller;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static User existingUser(Long tenantId) {
        Tenant tenant = Tenant.builder().id(tenantId).name("云舟科技").build();
        User u = User.builder().id(1L).username("zhangsan").role(UserRole.TENANT_MEMBER).build();
        u.setPassword("$2a$10$OLD_HASH_PLACEHOLDER");
        u.setNickname("旧昵称");
        u.setEnabled(true);
        u.setTenant(tenant);
        return u;
    }

    /** 桩 StpUtil.getSession().get("role")，模拟当前登录身份的角色。 */
    private static MockedStatic<StpUtil> stubRole(String role) {
        SaSession session = mock(SaSession.class);
        when(session.get("role")).thenReturn(role);
        MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::getSession).thenReturn(session);
        return stp;
    }

    /**
     * 变异实验：把第 174 行 {@code !request.getPassword().isBlank()} 改回
     * {@code !request.getPassword().isEmpty()}，本条必须转红——全空格密码会被当作「有意修改」
     * 写入，BCrypt.hashpw("   ") 落库，与空串同一后果：旧密码登不上、空白密码又被
     * {@code LoginRequest} 的 {@code @NotBlank} 拒绝，用户被彻底锁死。
     */
    @Test
    void 空白密码不改动密码哈希() {
        User existing = existingUser(2L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        TenantContext.setTenantId(2L);

        UserUpdateRequest req = new UserUpdateRequest();
        req.setNickname("新昵称");
        req.setPassword("   "); // 全空格，非空串但同样无意义

        try (MockedStatic<StpUtil> ignored = stubRole("TENANT_MEMBER")) {
            ApiResponse<User> resp = controller.updateUser(1L, req);

            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(resp.getData().getPassword()).isEqualTo("$2a$10$OLD_HASH_PLACEHOLDER");
            assertThat(resp.getData().getNickname()).isEqualTo("新昵称");
        }
    }

    /**
     * 变异实验：把第 164 行 {@code newRole == PLATFORM_ADMIN && !isPlatformAdmin()} 的判断
     * 删掉（即不做提权校验），本条必须转红——非平台管理员会成功把普通用户提升为
     * {@code PLATFORM_ADMIN}。
     */
    @Test
    void 非平台管理员把角色设为平台管理员被拒() {
        User existing = existingUser(2L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        TenantContext.setTenantId(2L);

        UserUpdateRequest req = new UserUpdateRequest();
        req.setRole(UserRole.PLATFORM_ADMIN);

        try (MockedStatic<StpUtil> ignored = stubRole("TENANT_ADMIN")) {
            ApiResponse<User> resp = controller.updateUser(1L, req);

            assertThat(resp.getCode()).isNotEqualTo(200);
            assertThat(resp.getMessage()).contains("无权设置为平台管理员");
        }
        // 拒绝分支必须在 save 之前短路，不能先落库再报错
        assertThat(existing.getRole()).isEqualTo(UserRole.TENANT_MEMBER);
        verify(userRepository, never()).save(any(User.class));
    }

    /**
     * 变异实验：把第 155-160 行 enabled 分支的管理员门禁
     * {@code if (isPlatformAdmin() || isTenantAdmin())} 删掉（即普通成员也能改自己的
     * enabled/role），本条必须转红——非管理员对同租户用户发起的 enabled/role 变更会被静默生效。
     */
    @Test
    void 非管理员改不动enabled和role() {
        User existing = existingUser(2L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        TenantContext.setTenantId(2L); // 与 existing 同租户，canAccessUser 放行

        UserUpdateRequest req = new UserUpdateRequest();
        req.setEnabled(false);
        req.setRole(UserRole.TENANT_ADMIN);
        req.setNickname("新昵称"); // 非管理员字段照常生效，证明请求本身被处理而非整体被拒

        try (MockedStatic<StpUtil> ignored = stubRole("TENANT_MEMBER")) {
            ApiResponse<User> resp = controller.updateUser(1L, req);

            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(resp.getData().getEnabled()).isTrue(); // 未被改动
            assertThat(resp.getData().getRole()).isEqualTo(UserRole.TENANT_MEMBER); // 未被改动
            assertThat(resp.getData().getNickname()).isEqualTo("新昵称");
        }
    }
}
