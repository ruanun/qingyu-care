package com.qingyu.care.support;

import com.qingyu.care.entity.Application;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.enums.TenantStatus;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.TenantRepository;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
// Boot 4 按技术栈拆分测试切片，MockMvc 支持迁到 spring-boot-webmvc-test 模块的新包
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
// Testcontainers 2.0 起容器类下沉到模块专属包（旧包仅作兼容保留）
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Paths;

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

    /**
     * PostgreSQLContainer(1.20.4) 未提供接收 Future&lt;String&gt;/ImageFromDockerfile 的构造函数，
     * 因此先同步构建镜像（.get() 触发 docker build 并返回镜像 tag），再包成兼容 DockerImageName 传入。
     * 首次构建含编译 zhparser 扩展，耗时数分钟；之后 Docker 本地镜像缓存复用，秒级启动。
     */
    // Testcontainers 2.0 起 PostgreSQLContainer 不再是泛型类
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse(buildZhparserImage())
                    .asCompatibleSubstituteFor("postgres"))
                    .withInitScript("db/init-vector.sql");

    static {
        POSTGRES.start();
    }

    private static String buildZhparserImage() {
        try {
            return new ImageFromDockerfile("qingyu/pgvector-zhparser:it", false)
                    .withDockerfile(Paths.get("../docker/postgres-zhparser/Dockerfile"))
                    .get();
        } catch (Exception e) {
            throw new IllegalStateException("构建 zhparser 镜像失败", e);
        }
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
