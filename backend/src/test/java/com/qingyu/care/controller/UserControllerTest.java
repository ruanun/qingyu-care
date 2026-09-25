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
    @Mock
    com.qingyu.care.service.SessionRevocationService sessionRevocationService;
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
        TenantContext.setUserId(1L); // 改的是自己：成员对他人资料/密码无权（见下方新增用例）

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
     * 反向断言：上一条只钉「空白不覆盖」是空转的——若有人把第 180-182 行整个 if 连同
     * {@code user.setPassword(...)} 一起删掉，上一条测试照样绿（密码本来就没变过）。
     * 本条钉住另一半：传真实新密码必须真的生效。断言哈希「变了」而非等于某个具体值——
     * {@code BCrypt.hashpw} 每次加盐结果不同，写死期望值必红。
     */
    @Test
    void 传真实新密码会更新密码哈希() {
        User existing = existingUser(2L);
        String oldHash = existing.getPassword();
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        TenantContext.setTenantId(2L);
        TenantContext.setUserId(1L); // 改自己的密码

        UserUpdateRequest req = new UserUpdateRequest();
        req.setPassword("NewRealPassword123");   // 真实新密码，而非空白

        try (MockedStatic<StpUtil> ignored = stubRole("TENANT_MEMBER")) {
            ApiResponse<User> resp = controller.updateUser(1L, req);

            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(resp.getData().getPassword())
                    .as("传入真实新密码必须真的生效，否则「空白不覆盖」的判空退化成「永不写入」")
                    .isNotEqualTo(oldHash);
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
        TenantContext.setUserId(1L);   // 改的是自己

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
    // ===== 安全审查 H3（2026-09-03）：成员越过租户管理员 =====

    /**
     * 变异实验：把 updateUser 里「改他人须管理员」的门禁删掉，本条转红——坐席对本租户管理员
     * 的 id 发一个 password 就能接管管理员账号。
     */
    @Test
    void 成员不能重置他人密码() {
        User existing = existingUser(2L);
        String oldHash = existing.getPassword();
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        TenantContext.setTenantId(2L);
        TenantContext.setUserId(99L); // 当前登录的是另一个成员

        UserUpdateRequest req = new UserUpdateRequest();
        req.setPassword("hacked123");

        try (MockedStatic<StpUtil> ignored = stubRole("TENANT_MEMBER")) {
            ApiResponse<User> resp = controller.updateUser(1L, req);

            assertThat(resp.getCode()).isEqualTo(403);
        }
        assertThat(existing.getPassword()).isEqualTo(oldHash);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void 成员不能改他人资料() {
        User existing = existingUser(2L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        TenantContext.setTenantId(2L);
        TenantContext.setUserId(99L);

        UserUpdateRequest req = new UserUpdateRequest();
        req.setNickname("被改名");

        try (MockedStatic<StpUtil> ignored = stubRole("TENANT_MEMBER")) {
            ApiResponse<User> resp = controller.updateUser(1L, req);

            assertThat(resp.getCode()).isEqualTo(403);
        }
        assertThat(existing.getNickname()).isEqualTo("旧昵称");
        verify(userRepository, never()).save(any(User.class));
    }

    /** 反向断言：门禁不能误伤租户管理员——管理员重置本租户成员密码是正当操作。 */
    @Test
    void 租户管理员可重置本租户成员密码() {
        User existing = existingUser(2L);
        String oldHash = existing.getPassword();
        when(userRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        TenantContext.setTenantId(2L);
        TenantContext.setUserId(99L);

        UserUpdateRequest req = new UserUpdateRequest();
        req.setPassword("ResetByAdmin123");

        try (MockedStatic<StpUtil> ignored = stubRole("TENANT_ADMIN")) {
            ApiResponse<User> resp = controller.updateUser(1L, req);

            assertThat(resp.getCode()).isEqualTo(200);
            assertThat(resp.getData().getPassword()).isNotEqualTo(oldHash);
        }
    }
    /** 安全审查 L11：非法枚举值曾一路穿到 UserRole.valueOf，变成 500 并回显内部类名。 */
    @Test
    void 创建用户时非法角色返回400() {
        TenantContext.setTenantId(2L);
        when(userRepository.existsByUsernameAcrossTenants("newbie")).thenReturn(false);

        try (MockedStatic<StpUtil> ignored = stubRole("TENANT_ADMIN")) {
            ApiResponse<User> resp = controller.createUser(java.util.Map.of(
                    "username", "newbie", "password", "pwd123456", "role", "SUPERUSER"));

            assertThat(resp.getCode()).isEqualTo(400);
            assertThat(resp.getMessage()).doesNotContain("com.qingyu");
        }
        verify(userRepository, never()).save(any(User.class));
    }
    // ===== 2026-09-25 安全扫描 M1：状态变化时吊销已签发的登录态 =====

    private ApiResponse<User> adminUpdates(UserUpdateRequest req) {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser(2L)));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        TenantContext.setTenantId(2L);
        TenantContext.setUserId(99L); // 管理员改别人
        try (MockedStatic<StpUtil> ignored = stubRole("TENANT_ADMIN")) {
            return controller.updateUser(1L, req);
        }
    }

    @Test
    void 改角色_停用_管理员重置密码都吊销该用户全部登录态() {
        UserUpdateRequest role = new UserUpdateRequest();
        role.setRole(UserRole.TENANT_ADMIN);
        adminUpdates(role);
        org.mockito.Mockito.verify(sessionRevocationService).revokeUser(1L, "角色变更");

        org.mockito.Mockito.reset(sessionRevocationService, userRepository);
        UserUpdateRequest disable = new UserUpdateRequest();
        disable.setEnabled(false);
        adminUpdates(disable);
        org.mockito.Mockito.verify(sessionRevocationService).revokeUser(1L, "账号停用");

        org.mockito.Mockito.reset(sessionRevocationService, userRepository);
        UserUpdateRequest pwd = new UserUpdateRequest();
        pwd.setPassword("NewPass123");
        adminUpdates(pwd);
        org.mockito.Mockito.verify(sessionRevocationService).revokeUser(1L, "管理员重置密码");
    }

    /** 只改资料不该把人踢下线；角色「改成原值」也不算变更 */
    @Test
    void 只改昵称或角色不变时不吊销() {
        UserUpdateRequest req = new UserUpdateRequest();
        req.setNickname("新昵称");
        req.setRole(UserRole.TENANT_MEMBER); // 与原角色相同
        req.setEnabled(true);
        adminUpdates(req);
        org.mockito.Mockito.verifyNoInteractions(sessionRevocationService);
    }

    @Test
    void 本人改密码只吊销其他设备上的登录态() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser(2L)));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        TenantContext.setTenantId(2L);
        TenantContext.setUserId(1L);
        UserUpdateRequest req = new UserUpdateRequest();
        req.setPassword("MyNewPass123");

        try (MockedStatic<StpUtil> ignored = stubRole("TENANT_MEMBER")) {
            controller.updateUser(1L, req);
        }

        org.mockito.Mockito.verify(sessionRevocationService).revokeOtherSessions(1L, "本人修改密码");
        org.mockito.Mockito.verify(sessionRevocationService, org.mockito.Mockito.never()).revokeUser(any(), any());
    }

    @Test
    void 删除用户吊销其登录态() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser(2L)));
        TenantContext.setTenantId(2L);
        TenantContext.setUserId(99L);
        try (MockedStatic<StpUtil> ignored = stubRole("TENANT_ADMIN")) {
            controller.deleteUser(1L);
        }
        org.mockito.Mockito.verify(sessionRevocationService).revokeUser(1L, "账号删除");
    }
}
