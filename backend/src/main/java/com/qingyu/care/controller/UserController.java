package com.qingyu.care.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.ChangePasswordRequest;
import com.qingyu.care.dto.UserUpdateRequest;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.service.LoginThrottleService;
import com.qingyu.care.service.SessionRevocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户管理 API
 * 供租户管理员管理其下的用户
 *
 * <p><b>本类的失败响应一律是「HTTP 200 + 显式 body.code」</b>——它们是正常 {@code return}
 * 而非抛异常，故不经 {@code GlobalExceptionHandler}，{@code code} 必须在调用点自己给。
 * ⚠️ <b>不能图省事用 {@code ApiResponse.error(String)}</b>：那个重载默认 500，而本类的失败
 * 全是业务校验（无权限、不存在、输入非法），没有一处是服务器内部错误。曾经 13 处全是 500，
 * 于 2026-09-02 按下表订正。
 *
 * <p>取值与 {@code GlobalExceptionHandler} 的口径对齐，便于前端一视同仁：
 * <ul>
 *   <li><b>403</b> 权限不足（同 {@code NotPermissionException}）；</li>
 *   <li><b>404</b> 资源不存在（同 {@code ResourceNotFoundException}）。⚠️「用户不存在<b>或</b>
 *       无权限访问」刻意合并成这一种响应，<b>不要拆开</b>——分别返回 404 与 403 等于告诉调用方
 *       「这个 id 确实存在」，是一条 id 枚举探测通道；</li>
 *   <li><b>400</b> 客户端输入或请求上下文问题（同 {@code InvalidOperationException}）。</li>
 * </ul>
 *
 * <p>⚠️ <b>别改成抛异常</b>（那样会走 {@code GlobalExceptionHandler} 的「真实 HTTP 状态码」
 * 路线）：管理端前端 axios 依赖「HTTP 200 + body.code」这条约定分流，4xx 会让它直接 reject，
 * 调用方那句 {@code if (res.code !== 200)} 根本走不到。
 *
 * <p>⚠️ <b>也别在这里用 401 表达「未登录」</b>：前端那条「清登录态并跳 /login」判的是
 * <b>HTTP 状态码</b>（{@code error.response?.status === 401}）且只在 axios reject 时才走，
 * 而本类恒返回 HTTP 200 → 走的是 success 分支，{@code code: 401} 于是被当成一条普通错误
 * 消息静默显示，登录态该清的没清。未登录本就由 Sa-Token 抛 {@code NotLoginException}
 * 处理（那条给真实 HTTP 401），轮不到这里表达。
 */
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final SessionRevocationService sessionRevocationService;
    private final LoginThrottleService loginThrottleService;

    /**
     * 获取当前租户下的所有用户
     */
    @GetMapping
    public ApiResponse<List<User>> listUsers() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            // 平台管理员可以查看所有用户
            if (isPlatformAdmin()) {
                return ApiResponse.success(userRepository.findAll());
            }
            return ApiResponse.error(400, "无法获取用户列表：未关联租户");
        }
        List<User> users = userRepository.findByTenantId(tenantId);
        return ApiResponse.success(users);
    }

    /**
     * 获取单个用户详情
     */
    @GetMapping("/{id}")
    public ApiResponse<User> getUser(@PathVariable Long id) {
        return userRepository.findById(id)
                .filter(user -> canAccessUser(user))
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "用户不存在或无权限访问"));
    }

    /**
     * 创建/邀请新用户（租户管理员）
     */
    @AuditLog(action = "USER_CREATE", targetType = "User")
    @PostMapping
    public ApiResponse<User> createUser(@RequestBody Map<String, Object> request) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null && !isPlatformAdmin()) {
            return ApiResponse.error(403, "只有平台管理员或租户管理员可以创建用户");
        }

        // 检查当前用户是否有权限创建用户
        if (!isPlatformAdmin() && !isTenantAdmin()) {
            return ApiResponse.error(403, "只有管理员可以创建用户");
        }

        String username = (String) request.get("username");
        String password = (String) request.get("password");
        String nickname = (String) request.get("nickname");
        String email = (String) request.get("email");
        String phone = (String) request.get("phone");
        String roleStr = (String) request.getOrDefault("role", "TENANT_MEMBER");

        // ⚠️ 必须判 isBlank 而非只判 null：全空格密码（如粘贴带尾随空格）一路穿过
        // BCrypt.hashpw(" ") 落库，与空串同一后果——旧密码登不上、空白密码又被
        // LoginRequest 的 @NotBlank 拒绝，账号从创建那一刻起就永久锁死且毫无提示。
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return ApiResponse.error(400, "用户名和密码不能为空");
        }

        // 检查用户名是否已存在。⚠️ 必须跨租户查：username 是全局唯一键，只在当前租户内查
        // 会把别租户已占用的名字判成可用，放行到 INSERT 撞唯一约束（500 + 泄漏 SQL）。
        if (userRepository.existsByUsernameAcrossTenants(username)) {
            // 同一语义在 AuthService.register 走 InvalidOperationException → 400，两条路径必须一致。
            return ApiResponse.error(400, "用户名已存在");
        }

        UserRole role;
        try {
            role = UserRole.valueOf(roleStr);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, "角色取值非法");
        }
        
        // 非平台管理员不能创建平台管理员
        if (role == UserRole.PLATFORM_ADMIN && !isPlatformAdmin()) {
            return ApiResponse.error(403, "无权创建平台管理员");
        }

        User user = User.builder()
                .username(username)
                .password(BCrypt.hashpw(password))
                .nickname(nickname)
                .email(email)
                .phone(phone)
                .role(role)
                .enabled(true)
                .build();

        // 关联租户：优先当前租户上下文，其次请求显式指定（供平台管理员跨租户创建）
        Long targetTenantId = tenantId != null
                ? tenantId
                : (request.get("tenantId") != null ? Long.valueOf(request.get("tenantId").toString()) : null);
        if (targetTenantId != null) {
            Tenant tenant = tenantRepository.findById(targetTenantId).orElse(null);
            if (tenant == null) {
                return ApiResponse.error(404, "指定的租户不存在");
            }
            user.setTenant(tenant);
        }

        // 租户内角色（TENANT_ADMIN/TENANT_MEMBER）必须归属某个租户，
        // 避免产生"无租户成员"脏数据（会绕过租户数据隔离）
        if (role != UserRole.PLATFORM_ADMIN && user.getTenant() == null) {
            return ApiResponse.error(400, "创建租户管理员/成员时必须指定所属租户");
        }

        User savedUser = userRepository.save(user);
        log.info("用户创建成功: username={}, role={}", username, role);
        return ApiResponse.success("用户创建成功", savedUser);
    }

    /**
     * 本人修改密码：必须验证当前密码（2026-09-30 安全审查 M3）。任何已登录角色都可调用，坐席此前在界面上根本改不了自己的密码。
     *
     * <p>猜当前密码复用登录的失败计数（同一用户名的同一个键）：否则持有被盗 token 的人能在这里无限次猜原密码，
     * 绕开登录页的锁定。猜错 5 次后登录与这里一起锁 15 分钟。成功后其他设备的登录态失效，本设备保留。
     */
    @PostMapping("/me/password")
    @AuditLog(action = "CHANGE_OWN_PASSWORD", targetType = "User")
    public ApiResponse<Void> changeMyPassword(@Valid @RequestBody ChangePasswordRequest request) {
        Long userId = TenantContext.getUserId();
        if (userId == null) {
            return ApiResponse.error(400, "无法识别当前用户");
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ApiResponse.error(404, "用户不存在或无权限访问");
        }
        loginThrottleService.acquireAttempt(user.getUsername());
        if (!BCrypt.checkpw(request.getOldPassword(), user.getPassword())) {
            // 名额保留：这就是一次失败的凭据尝试
            return ApiResponse.error(400, "当前密码不正确");
        }
        loginThrottleService.clearFailures(user.getUsername());
        user.setPassword(BCrypt.hashpw(request.getNewPassword()));
        userRepository.save(user);
        sessionRevocationService.revokeOtherSessions(user.getId(), "本人修改密码");
        return ApiResponse.success("密码已修改，其他设备需要重新登录", null);
    }

    /**
     * 更新用户信息
     */
    @AuditLog(action = "USER_UPDATE", targetType = "User")
    @PutMapping("/{id}")
    public ApiResponse<User> updateUser(@PathVariable Long id, @RequestBody UserUpdateRequest request) {
        return userRepository.findById(id)
                .filter(this::canAccessUser)
                .map(user -> {
                    // 安全审查 H3（2026-09-03）：canAccessUser 只判「同租户」，此前坐席对本租户管理员
                    // 的 id 发一个 password 就能接管管理员账号。改他人（含密码）必须是管理员；
                    // 非管理员只能改自己。
                    boolean self = user.getId() != null && user.getId().equals(TenantContext.getUserId());
                    if (!self && !isPlatformAdmin() && !isTenantAdmin()) {
                        return ApiResponse.<User>error(403, "无权修改其他用户");
                    }
                    UserRole oldRole = user.getRole();
                    boolean wasEnabled = Boolean.TRUE.equals(user.getEnabled());
                    boolean passwordChanged = false;
                    if (request.getNickname() != null) {
                        user.setNickname(request.getNickname());
                    }
                    if (request.getEmail() != null) {
                        user.setEmail(request.getEmail());
                    }
                    if (request.getPhone() != null) {
                        user.setPhone(request.getPhone());
                    }
                    if (request.getAvatar() != null) {
                        user.setAvatar(request.getAvatar());
                    }
                    if (request.getEnabled() != null) {
                        // 只有管理员可以启用/禁用用户
                        if (isPlatformAdmin() || isTenantAdmin()) {
                            user.setEnabled(request.getEnabled());
                        }
                    }
                    if (request.getRole() != null && (isPlatformAdmin() || isTenantAdmin())) {
                        UserRole newRole = request.getRole();
                        // 非平台管理员不能将用户提升为平台管理员
                        if (newRole == UserRole.PLATFORM_ADMIN && !isPlatformAdmin()) {
                            return ApiResponse.<User>error(403, "无权设置为平台管理员");
                        }
                        user.setRole(newRole);
                    }
                    // ⚠️ 必须判 isBlank 而非只判空串：前端编辑弹窗把 password 初始化为空串
                    // （注释写着「编辑时不显示/修改密码除非输入」）却在提交时一起发出。原先用
                    // containsKey("password") 判断，空串同样成立 → BCrypt.hashpw("")，后果是
                    // 管理员改一下昵称，该用户密码被静默重置成空串哈希：旧密码登不上，空密码
                    // 又被 LoginRequest 的 @NotBlank 拒掉，用户被彻底锁死且毫无提示。仅判
                    // isEmpty 挡不住全空格密码（"   "）——JS/Java 都认为它「非空」，同样一路
                    // 穿到 BCrypt.hashpw("   ")，后果与空串完全一致，只是构造方式换成粘贴带
                    // 尾随空格，比空串更容易无意间触发。
                    if (request.getPassword() != null && !request.getPassword().isBlank()) {
                        if (self) {
                            // 本人改密必须验证当前密码，走 POST /me/password（2026-09-30 安全审查 M3）：
                            // 否则偷到的 token 就能改密接管账号，还会把真正的主人从其他设备踢下线
                            return ApiResponse.<User>error(400, "修改自己的密码请使用「修改密码」，需要验证当前密码");
                        }
                        user.setPassword(BCrypt.hashpw(request.getPassword()));
                        passwordChanged = true;
                    }
                    User savedUser = userRepository.save(user);
                    // 已签发的登录态随状态变化失效（2026-09-25 安全扫描 M1）：角色存在会话里，不踢的话降级要等
                    // 对方自己重登才生效；停用、改密后旧 token 也照用不误
                    boolean roleChanged = savedUser.getRole() != oldRole;
                    boolean disabled = wasEnabled && !Boolean.TRUE.equals(savedUser.getEnabled());
                    if (roleChanged || disabled || (passwordChanged && !self)) {
                        sessionRevocationService.revokeUser(savedUser.getId(),
                                roleChanged ? "角色变更" : disabled ? "账号停用" : "管理员重置密码");
                    } else if (passwordChanged) {
                        // 自己改密码：别的设备上的会话失效，但不把正在操作的自己踢回登录页
                        sessionRevocationService.revokeOtherSessions(savedUser.getId(), "本人修改密码");
                    }
                    return ApiResponse.success("用户更新成功", savedUser);
                })
                .orElse(ApiResponse.error(404, "用户不存在或无权限访问"));
    }

    /**
     * 删除用户
     */
    @AuditLog(action = "USER_DELETE", targetType = "User")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable Long id) {
        return userRepository.findById(id)
                .filter(this::canAccessUser)
                .map(user -> {
                    // 不能删除自己
                    if (user.getId().equals(TenantContext.getUserId())) {
                        return ApiResponse.<Void>error(400, "不能删除自己的账号");
                    }
                    // 只有管理员可以删除用户
                    if (!isPlatformAdmin() && !isTenantAdmin()) {
                        return ApiResponse.<Void>error(403, "只有管理员可以删除用户");
                    }
                    userRepository.delete(user);
                    sessionRevocationService.revokeUser(id, "账号删除");
                    log.info("用户删除成功: id={}", id);
                    return ApiResponse.<Void>success("用户删除成功", null);
                })
                .orElse(ApiResponse.error(404, "用户不存在或无权限访问"));
    }

    /**
     * 检查是否为平台管理员
     */
    private boolean isPlatformAdmin() {
        Object role = StpUtil.getSession().get("role");
        return role != null && UserRole.PLATFORM_ADMIN.name().equals(role.toString());
    }

    /**
     * 检查是否为租户管理员
     */
    private boolean isTenantAdmin() {
        Object role = StpUtil.getSession().get("role");
        return role != null && UserRole.TENANT_ADMIN.name().equals(role.toString());
    }

    /**
     * 检查当前用户是否可以访问指定用户
     */
    private boolean canAccessUser(User user) {
        // 平台管理员可以访问所有用户
        if (isPlatformAdmin()) {
            return true;
        }
        // 租户用户只能访问同租户的用户
        Long currentTenantId = TenantContext.getTenantId();
        if (currentTenantId != null && user.getTenant() != null) {
            return currentTenantId.equals(user.getTenant().getId());
        }
        return false;
    }
}
