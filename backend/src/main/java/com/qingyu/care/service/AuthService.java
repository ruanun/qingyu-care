package com.qingyu.care.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.LoginRequest;
import com.qingyu.care.dto.LoginResponse;
import com.qingyu.care.dto.RegisterRequest;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.entity.SysAuditLog;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 认证服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {
    
    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final ConfigService configService;
    private final AuditLogService auditLogService;
    
    /**
     * 初始化平台管理员
     */
    @PostConstruct
    @Transactional
    public void init() {
        if (!userRepository.existsByUsername("admin")) {
            log.info("Creating platform admin user...");
            User admin = User.builder()
                    .username("admin")
                    .password(BCrypt.hashpw("admin123"))
                    .nickname("平台管理员")
                    .role(UserRole.PLATFORM_ADMIN)
                    .enabled(true)
                    .build();
            userRepository.save(admin);
            log.info("Platform admin created: admin / admin123");
        }
    }
    
    /**
     * 用户登录
     */
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new InvalidOperationException("用户名或密码错误"));
        
        if (!BCrypt.checkpw(request.getPassword(), user.getPassword())) {
            throw new InvalidOperationException("用户名或密码错误");
        }
        
        if (!user.getEnabled()) {
            throw new InvalidOperationException("账号已被禁用");
        }
        
        // 检查租户状态
        if (user.getTenant() != null && user.getTenant().getStatus() != TenantStatus.ACTIVE) {
            throw new InvalidOperationException("租户已被冻结或过期");
        }
        
        // Sa-Token 登录
        StpUtil.login(user.getId());
        
        // 存储额外信息到 Session
        StpUtil.getSession().set("userId", user.getId());
        StpUtil.getSession().set("username", user.getUsername());
        StpUtil.getSession().set("role", user.getRole().name());
        if (user.getTenant() != null) {
            StpUtil.getSession().set("tenantId", user.getTenant().getId());
        }
        
        // 更新最后登录时间
        user.setLastLoginTime(LocalDateTime.now());
        userRepository.save(user);

        log.info("User {} logged in successfully", user.getUsername());

        // 异步记录登录审计日志
        auditLogService.saveAsync(SysAuditLog.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .tenantId(user.getTenant() != null ? user.getTenant().getId() : null)
                .action("LOGIN")
                .targetType("User")
                .targetId(user.getId().toString())
                .detail("用户登录成功")
                .build());

        return LoginResponse.builder()
                .token(StpUtil.getTokenValue())
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .role(user.getRole())
                .tenantId(user.getTenant() != null ? user.getTenant().getId() : null)
                .tenantName(user.getTenant() != null ? user.getTenant().getName() : null)
                .build();
    }
    
    /**
     * 用户注册 (同时创建租户)
     */
    @Transactional
    public LoginResponse register(RegisterRequest request) {
        // 平台级"开放注册"开关：默认开启
        if (!configService.getBoolean(OwnerScope.PLATFORM, null,
                "platform.registration.enabled", true)) {
            throw new BusinessException(403, "平台已关闭自助注册，请联系管理员");
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new InvalidOperationException("用户名已存在");
        }
        
        Tenant tenant;
        if (request.getTenantCode() != null && !request.getTenantCode().isEmpty()) {
            // 加入已有租户
            tenant = tenantRepository.findByCode(request.getTenantCode())
                    .orElseThrow(() -> new ResourceNotFoundException("租户", request.getTenantCode()));
        } else {
            // 创建新租户
            String tenantCode = "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            tenant = Tenant.builder()
                    .name(request.getTenantName() != null ? request.getTenantName() : request.getUsername() + "的企业")
                    .code(tenantCode)
                    .status(TenantStatus.ACTIVE)
                    .plan(resolveDefaultPlan())
                    .contactName(request.getNickname())
                    .contactEmail(request.getEmail())
                    .contactPhone(request.getPhone())
                    .build();
            tenant = tenantRepository.save(tenant);
            log.info("New tenant created: {} ({})", tenant.getName(), tenant.getCode());
        }
        
        // 创建用户
        User user = User.builder()
                .username(request.getUsername())
                .password(BCrypt.hashpw(request.getPassword()))
                .nickname(request.getNickname())
                .email(request.getEmail())
                .phone(request.getPhone())
                .tenant(tenant)
                .role(UserRole.TENANT_ADMIN) // 注册用户默认为租户管理员
                .enabled(true)
                .build();
        user = userRepository.save(user);
        
        log.info("User {} registered successfully", user.getUsername());
        
        // 自动登录
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername(request.getUsername());
        loginRequest.setPassword(request.getPassword());
        return login(loginRequest);
    }

    /**
     * 解析平台配置的「新租户默认套餐」，未配置或取值非法时回落 FREE
     */
    private TenantPlan resolveDefaultPlan() {
        String planName = configService.get(OwnerScope.PLATFORM, null,
                "platform.default.plan", TenantPlan.FREE.name());
        try {
            return TenantPlan.valueOf(planName.trim().toUpperCase());
        } catch (Exception e) {
            log.warn("平台配置 platform.default.plan 取值非法: {}, 回落为 FREE", planName);
            return TenantPlan.FREE;
        }
    }
    
    /**
     * 退出登录
     */
    public void logout() {
        StpUtil.logout();
    }
    
    /**
     * 获取当前登录用户
     */
    public User getCurrentUser() {
        Long userId = StpUtil.getLoginIdAsLong();
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("用户", userId.toString()));
    }
    
    /**
     * 获取当前租户 ID
     */
    public Long getCurrentTenantId() {
        Object tenantId = StpUtil.getSession().get("tenantId");
        return tenantId != null ? Long.valueOf(tenantId.toString()) : null;
    }
    
    /**
     * 获取当前用户角色
     */
    public UserRole getCurrentRole() {
        String role = (String) StpUtil.getSession().get("role");
        return role != null ? UserRole.valueOf(role) : null;
    }
    
    /**
     * 检查是否为平台管理员
     */
    public boolean isPlatformAdmin() {
        return getCurrentRole() == UserRole.PLATFORM_ADMIN;
    }
}
