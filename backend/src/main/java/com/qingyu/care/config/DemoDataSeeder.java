package com.qingyu.care.config;

import cn.dev33.satoken.secure.BCrypt;
import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.entity.User;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.enums.UserRole;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 演示数据播种：一条命令产出可截图、可试用的完整数据。
 *
 * <p><b>启动方式</b>：{@code mvn spring-boot:run -Dspring-boot.run.profiles=dev,demo}
 *
 * <p><b>为什么是 ApplicationRunner 而不是 @PostConstruct</b>：四个配置服务
 * （ChatStrategyService / HandoverRuleService / PromptTemplateService / ModelRouterService）
 * 在 {@code @PostConstruct} 里播种 {@code tenant_id IS NULL} 的平台默认行，演示数据要能继承
 * 它们就必须排在其后。{@code @PostConstruct} 之间的相对顺序取决于 bean 依赖图，是隐式且
 * 脆弱的；ApplicationRunner 在容器完全就绪后执行，顺序确定。
 *
 * <p><b>为什么是 @Profile 而不是配置开关</b>：profile 是编译期即可见的隔离边界，生产启动
 * 不激活 demo 时这个 bean 根本不会被创建，不存在「配置写错了往生产库塞演示数据」的可能。
 *
 * @author ruan
 */
@Slf4j
@Component
@Profile("demo")
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    /** 幂等键：这个 code 的租户存在即认为演示数据已播种过 */
    static final String DEMO_TENANT_CODE = "demo-cloudboat";

    /**
     * 演示应用的 appKey/appSecret 固定而非随机——使用手册与 README 里的 Widget 嵌入代码
     * 才能直接写死它，读者照抄就能跑通。文档中须标注「演示专用，生产必须重新生成」。
     */
    static final String DEMO_APP_KEY = "demo-cloudboat-shop";
    static final String DEMO_APP_SECRET = "demo-secret-cloudboat-shop-0001";

    private static final String DEMO_PASSWORD = "demo123456";

    private final TenantRepository tenantRepository;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (tenantRepository.findByCode(DEMO_TENANT_CODE).isPresent()) {
                log.info("演示数据已存在（租户 code={}），跳过播种", DEMO_TENANT_CODE);
                return;
            }
            log.info("开始播种演示数据...");
            seed();
            log.info("演示数据播种完成");
        } catch (Exception e) {
            // 演示数据是旁路不是主链路，播种失败不该让应用起不来
            log.error("演示数据播种失败（不影响应用启动）: {}", e.getMessage(), e);
        }
    }

    private void seed() {
        Tenant tenant = seedTenant();
        User admin = seedUser(tenant, "demo_admin", "云舟管理员", UserRole.TENANT_ADMIN);
        seedUser(tenant, "demo_agent", "云舟客服小舟", UserRole.TENANT_MEMBER);
        Application shopApp = seedApp(tenant, "云舟商城客服", DEMO_APP_KEY, DEMO_APP_SECRET,
                "您好！我是云舟商城的智能客服小舟，可以帮您查询订单、退换货政策、配送与积分规则～");
        seedApp(tenant, "云舟内部 IT 助手", "demo-cloudboat-it", "demo-secret-cloudboat-it-0001",
                "您好，我是云舟内部 IT 助手，可以帮您解答办公设备、账号权限与网络相关问题。");
        log.info("演示租户/应用/用户就绪 tenantId={} appId={} adminId={}",
                tenant.getId(), shopApp.getId(), admin.getId());
    }

    private Tenant seedTenant() {
        return tenantRepository.save(Tenant.builder()
                .name("云舟科技")
                .code(DEMO_TENANT_CODE)
                .contactName("周云舟")
                .contactPhone("13800000000")
                .contactEmail("demo@cloudboat.example.com")
                .status(TenantStatus.ACTIVE)
                .plan(TenantPlan.PRO)
                .build());
    }

    private User seedUser(Tenant tenant, String username, String nickname, UserRole role) {
        return userRepository.save(User.builder()
                .tenant(tenant)
                .username(username)
                .password(BCrypt.hashpw(DEMO_PASSWORD))
                .nickname(nickname)
                .email(username + "@cloudboat.example.com")
                .role(role)
                .enabled(true)
                .build());
    }

    private Application seedApp(Tenant tenant, String name, String appKey, String appSecret,
                                String welcomeMessage) {
        return applicationRepository.save(Application.builder()
                .tenant(tenant)
                .name(name)
                .appKey(appKey)
                .appSecret(appSecret)
                .description(name + "（演示应用，appKey/appSecret 为固定演示值，生产必须重新生成）")
                .welcomeMessage(welcomeMessage)
                .themeColor("#6366f1")
                .enabled(true)
                .rateLimitPerMinute(60)
                .allowedOrigins("http://localhost:5173,http://localhost:3000")
                .build());
    }
}
