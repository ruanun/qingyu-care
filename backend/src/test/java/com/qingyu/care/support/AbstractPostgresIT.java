package com.qingyu.care.support;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.TenantRepository;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基类：单例 pgvector 容器 + MockMvc + 播种辅助。
 * 容器 static 单例，JVM 内跨全部 IT 复用（不随类停启，加速）。
 * Tag "integration-test" 让 Surefire 在单元测试阶段跳过这些类。
 *
 * @author ruan
 */
@Tag("integration-test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"))
                    .withInitScript("db/init-vector.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected TenantRepository tenantRepository;
    @Autowired
    protected ApplicationRepository applicationRepository;

    /** 播种一个租户 + 应用（Application 未继承 BaseTenantEntity，tenant_id 走关系映射，无需 TenantContext）。 */
    protected Application seedApp(String suffix, String allowedOrigins) {
        Tenant tenant = tenantRepository.save(Tenant.builder()
                .name("IT租户-" + suffix).code("IT_" + suffix)
                .status(TenantStatus.ACTIVE).plan(TenantPlan.FREE).build());
        Application app = new Application();
        app.setName("IT应用-" + suffix);
        app.setTenant(tenant);
        app.setAppKey("app_it_" + suffix);
        app.setAppSecret("sec_it_" + suffix);
        app.setEnabled(true);
        app.setAllowedOrigins(allowedOrigins);
        return applicationRepository.save(app);
    }
}
