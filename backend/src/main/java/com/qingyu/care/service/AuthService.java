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
import com.qingyu.care.exception.BadCredentialsException;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.UserRepository;
import com.qingyu.care.service.captcha.CaptchaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
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
    private final CaptchaService captchaService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 平台管理员初始口令（{@code QINGYU_ADMIN_INITIAL_PASSWORD}）。为空则首启时生成随机口令、
     * 只打一次 WARN——绝不能回落到写死的 admin123（安全审查 H1，2026-09-03）。
     */
    @Value("${qingyu.care.auth.self-register-enabled:false}")
    private boolean selfRegisterDefault;

    @Value("${qingyu.care.platform.admin-initial-password:}")
    private String adminInitialPassword;

    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";

    /**
     * 初始化平台管理员
     */
    @PostConstruct
    @Transactional
    public void init() {
        if (userRepository.existsByUsernameAcrossTenants("admin")) {
            return;
        }
        boolean generated = adminInitialPassword == null || adminInitialPassword.isBlank();
        String password = generated ? generateInitialPassword() : adminInitialPassword;
        log.info("Creating platform admin user...");
        User admin = User.builder()
                .username("admin")
                .password(BCrypt.hashpw(password))
                .nickname("平台管理员")
                .role(UserRole.PLATFORM_ADMIN)
                .enabled(true)
                .build();
        userRepository.save(admin);
        if (generated) {
            log.warn("平台管理员 admin 已创建，初始口令: {} —— 仅显示这一次，请立即登录修改；"
                    + "生产环境请用环境变量 QINGYU_ADMIN_INITIAL_PASSWORD 指定", password);
        } else {
            log.info("平台管理员 admin 已按配置的初始口令创建");
        }
    }

    private static String generateInitialPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }
    
    /**
     * 与真实哈希同轮数（$2a$10$）的占位哈希，只用于让「用户名不存在」与「密码错」耗时一致。
     * 写成预先算好的常量：在类加载时 hashpw 会给每次启动、每个加载该类的测试 JVM 平白多一次 BCrypt。
     */
    private static final String DUMMY_HASH = "$2a$10$iXO0qIc9GDpyfuT.JQZY0.eIrsLJQu4RyOxw7jEcpE8h4HVZpRmkC";

    /**
     * 用户登录
     */
    public LoginResponse login(LoginRequest request) {
        // 跨租户查：username 是全局唯一键，且带着上一个账号 satoken 的请求会让租户过滤器
        // 在这里生效，把别租户的账号挡成「用户名或密码错误」（详见 UserRepository 上的说明）。
        User user = userRepository.findByUsernameAcrossTenants(request.getUsername()).orElse(null);
        if (user == null) {
            // 不存在的用户名也跑一次 BCrypt：否则直接抛只要约 1ms、存在时要 50~100ms，响应耗时本身就能探测
            // 哪些用户名存在——锁定计数对不存在的用户名同样计数，就是为了不泄露这一点（2026-09-30 安全审查）
            BCrypt.checkpw(request.getPassword(), DUMMY_HASH);
            throw new BadCredentialsException();
        }
        
        if (!BCrypt.checkpw(request.getPassword(), user.getPassword())) {
            throw new BadCredentialsException();
        }
        
        if (!user.getEnabled()) {
            throw new InvalidOperationException("账号已被禁用");
        }
        
        // 检查租户状态与套餐到期（安全审查 M6：expireTime 此前从不参与判断）
        if (user.getTenant() != null && user.getTenant().getStatus() != TenantStatus.ACTIVE) {
            throw new InvalidOperationException("租户已被冻结或过期");
        }
        if (user.getTenant() != null && user.getTenant().getExpireTime() != null
                && user.getTenant().getExpireTime().isBefore(LocalDateTime.now())) {
            throw new InvalidOperationException("租户套餐已过期，请联系平台管理员");
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
     * 自助注册是否开放：平台配置中心的 {@code platform.registration.enabled} 优先（平台管理员在后台可切换），
     * 库里没配时取部署配置 {@code qingyu.care.auth.self-register-enabled}——基线 false，dev/test 为 true。
     * 此前库里没配时默认开启：任何人都能无限开租户（2026-09-25 安全扫描 M2）。
     */
    public boolean isSelfRegisterEnabled() {
        return configService.getBoolean(OwnerScope.PLATFORM, null, "platform.registration.enabled", selfRegisterDefault);
    }

    /**
     * 用户注册 (同时创建租户)
     *
     * <p>⚠️ 自助注册<b>只能建新租户</b>。此前带 {@code tenantCode} 即可加入已有租户且身份是
     * {@code TENANT_ADMIN}——租户 code 不是秘密（平台管理员自己填的、演示租户的写在 README 里），
     * 等于任何人都能自封任意租户的管理员（安全审查 H2，2026-09-03）。加入已有租户只能由该租户
     * 管理员在「成员管理」里创建账号。
     *
     * <p>事务只包「建租户 + 建用户」，用 {@link TransactionTemplate} 而非方法级 {@code @Transactional}：
     * 验证码校验在 Turnstile 模式下要同步请求 Cloudflare（最坏 5s 连接 + 8s 读），包进事务就得一直占着一个数据库连接——
     * Cloudflare 一慢，分散 IP 的注册请求就能把连接池占满，把「只影响注册」的 fail-closed 放大成全站不可用。
     * （此前 {@code @Transactional} 错放在 {@link #isSelfRegisterEnabled()} 上，建租户与建用户并不在同一事务里。）
     */
    public LoginResponse register(RegisterRequest request, String clientIp) {
        if (!isSelfRegisterEnabled()) {
            throw new BusinessException(403, "平台已关闭自助注册，请联系管理员");
        }
        // 验证码必须在查重名之前：否则不过验证码也能拿「用户名已存在」逐个探测账号
        captchaService.verify(request.getCaptchaId(), request.getCaptchaCode(), request.getTurnstileToken(), clientIp);
        if (request.getTenantCode() != null && !request.getTenantCode().isBlank()) {
            throw new BusinessException(403,
                    "自助注册只能创建新租户；加入已有租户请联系该租户管理员在「成员管理」中为你创建账号");
        }
        // 跨租户查：只在当前租户内查重名，会把重名放行到 INSERT 撞唯一约束（500 + 泄漏 SQL）。
        if (userRepository.existsByUsernameAcrossTenants(request.getUsername())) {
            throw new InvalidOperationException("用户名已存在");
        }

        String tenantCode = "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantPlan plan = resolveDefaultPlan();
        // BCrypt 是刻意的慢计算，同样放在事务外
        String passwordHash = BCrypt.hashpw(request.getPassword());
        transactionTemplate.executeWithoutResult(status -> {
            // 创建新租户
            Tenant tenant = tenantRepository.save(Tenant.builder()
                    .name(request.getTenantName() != null ? request.getTenantName() : request.getUsername() + "的企业")
                    .code(tenantCode)
                    .status(TenantStatus.ACTIVE)
                    .plan(plan)
                    .contactName(request.getNickname())
                    .contactEmail(request.getEmail())
                    .contactPhone(request.getPhone())
                    .build());
            log.info("New tenant created: {} ({})", tenant.getName(), tenant.getCode());

            // 创建用户
            User user = userRepository.save(User.builder()
                    .username(request.getUsername())
                    .password(passwordHash)
                    .nickname(request.getNickname())
                    .email(request.getEmail())
                    .phone(request.getPhone())
                    .tenant(tenant)
                    .role(UserRole.TENANT_ADMIN) // 注册用户默认为租户管理员
                    .enabled(true)
                    .build());
            log.info("User {} registered successfully", user.getUsername());
        });
        
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
