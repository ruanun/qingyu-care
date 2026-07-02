package com.qingyu.care.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ApiResponse;
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

        if (username == null || password == null) {
            return ApiResponse.error("用户名和密码不能为空");
        }

        // 检查用户名是否已存在
        if (userRepository.findByUsername(username).isPresent()) {
            return ApiResponse.error("用户名已存在");
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
    public ApiResponse<User> updateUser(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        return userRepository.findById(id)
                .filter(this::canAccessUser)
                .map(user -> {
                    if (request.containsKey("nickname")) {
                        user.setNickname((String) request.get("nickname"));
                    }
                    if (request.containsKey("email")) {
                        user.setEmail((String) request.get("email"));
                    }
                    if (request.containsKey("phone")) {
                        user.setPhone((String) request.get("phone"));
                    }
                    if (request.containsKey("avatar")) {
                        user.setAvatar((String) request.get("avatar"));
                    }
                    if (request.containsKey("enabled")) {
                        // 只有管理员可以启用/禁用用户
                        if (isPlatformAdmin() || isTenantAdmin()) {
                            user.setEnabled((Boolean) request.get("enabled"));
                        }
                    }
                    if (request.containsKey("role") && (isPlatformAdmin() || isTenantAdmin())) {
                        UserRole newRole = UserRole.valueOf((String) request.get("role"));
                        // 非平台管理员不能将用户提升为平台管理员
                        if (newRole == UserRole.PLATFORM_ADMIN && !isPlatformAdmin()) {
                            return ApiResponse.<User>error("无权设置为平台管理员");
                        }
                        user.setRole(newRole);
                    }
                    if (request.containsKey("password")) {
                        user.setPassword(BCrypt.hashpw((String) request.get("password")));
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
