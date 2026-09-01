package com.qingyu.care.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.UserUpdateRequest;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 用户管理 API
 * 供租户管理员管理其下的用户
 */
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;

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
            return ApiResponse.error("无法获取用户列表：未关联租户");
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
                .orElse(ApiResponse.error("用户不存在或无权限访问"));
    }

    /**
     * 创建/邀请新用户（租户管理员）
     */
    @AuditLog(action = "USER_CREATE", targetType = "User")
    @PostMapping
    public ApiResponse<User> createUser(@RequestBody Map<String, Object> request) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null && !isPlatformAdmin()) {
            return ApiResponse.error("只有平台管理员或租户管理员可以创建用户");
        }

        // 检查当前用户是否有权限创建用户
        if (!isPlatformAdmin() && !isTenantAdmin()) {
            return ApiResponse.error("只有管理员可以创建用户");
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
            return ApiResponse.error("用户名和密码不能为空");
        }

        // 检查用户名是否已存在。⚠️ 必须跨租户查：username 是全局唯一键，只在当前租户内查
        // 会把别租户已占用的名字判成可用，放行到 INSERT 撞唯一约束（500 + 泄漏 SQL）。
        if (userRepository.existsByUsernameAcrossTenants(username)) {
            // 显式 400：这是用户输入问题，不是服务器内部错误。同一语义在 AuthService.register
            // 走 InvalidOperationException → 400，两条路径必须一致。
            // ⚠️ 本文件其余 ApiResponse.error(...) 仍是默认的 500，属既有的语义不一致，另记待办。
            return ApiResponse.error(400, "用户名已存在");
        }

        UserRole role = UserRole.valueOf(roleStr);
        
        // 非平台管理员不能创建平台管理员
        if (role == UserRole.PLATFORM_ADMIN && !isPlatformAdmin()) {
            return ApiResponse.error("无权创建平台管理员");
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
                return ApiResponse.error("指定的租户不存在");
            }
            user.setTenant(tenant);
        }

        // 租户内角色（TENANT_ADMIN/TENANT_MEMBER）必须归属某个租户，
        // 避免产生"无租户成员"脏数据（会绕过租户数据隔离）
        if (role != UserRole.PLATFORM_ADMIN && user.getTenant() == null) {
            return ApiResponse.error("创建租户管理员/成员时必须指定所属租户");
        }

        User savedUser = userRepository.save(user);
        log.info("用户创建成功: username={}, role={}", username, role);
        return ApiResponse.success("用户创建成功", savedUser);
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
                            return ApiResponse.<User>error("无权设置为平台管理员");
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
                        user.setPassword(BCrypt.hashpw(request.getPassword()));
                    }
                    User savedUser = userRepository.save(user);
                    return ApiResponse.success("用户更新成功", savedUser);
                })
                .orElse(ApiResponse.error("用户不存在或无权限访问"));
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
                        return ApiResponse.<Void>error("不能删除自己的账号");
                    }
                    // 只有管理员可以删除用户
                    if (!isPlatformAdmin() && !isTenantAdmin()) {
                        return ApiResponse.<Void>error("只有管理员可以删除用户");
                    }
                    userRepository.delete(user);
                    log.info("用户删除成功: id={}", id);
                    return ApiResponse.<Void>success("用户删除成功", null);
                })
                .orElse(ApiResponse.error("用户不存在或无权限访问"));
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
