package com.qingyu.care.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 占位符密钥没改就拒绝启动（2026-09-25 安全扫描 M4）。
 *
 * <p>{@code .env.example} 里的密钥一律写成 {@code change-me-in-production}，本意是提醒运维改掉；可一旦忘了，
 * 它就是真实密码——尤其平台管理员初始口令，照模板部署的实例用 admin / change-me-in-production 就能登录。
 * 这类失误没有任何报错，所以在启动时拦：非 dev/test profile 下，下列变量任一仍含 {@code change-me} 即抛异常，
 * Bean 初始化阶段就失败，Web 服务根本起不来。
 *
 * <p>按环境变量名读取而非 Spring 属性名：存储密钥挂在 {@code dromara.x-file-storage} 的列表配置下不好取，
 * 而且报错里直接写变量名，运维一眼知道该改 .env 的哪一行。
 *
 * @author ruan
 */
@Slf4j
@Component
public class ProductionSecretsGuard implements InitializingBean {

    /**
     * 实际生效的密钥属性 → 对应的环境变量名（报错时告诉运维改哪一行）。新增密钥类配置时同步加在这里。
     *
     * <p>按<b>实际生效的属性值</b>查，而不是按环境变量名查（2026-09-30 安全审查）：环境变量经占位符或宽松绑定最终都落到
     * 这些属性上，所以按属性查是它的超集；而只查环境变量拦不住「不用 compose、直接跑 jar」——变量没设，
     * application.yml 里的默认值（{@code postgres}、{@code rustfsadmin123}）照样生效，按变量名查看到的却是 null。
     */
    static final Map<String, String> EFFECTIVE_SECRETS = new LinkedHashMap<>();
    static {
        EFFECTIVE_SECRETS.put("spring.datasource.password", "SPRING_DATASOURCE_PASSWORD");
        EFFECTIVE_SECRETS.put("dromara.x-file-storage.amazon-s3-v2[0].secret-key", "STORAGE_SECRET_KEY");
        EFFECTIVE_SECRETS.put("dromara.x-file-storage.amazon-s3-v2[0].access-key", "STORAGE_ACCESS_KEY");
        EFFECTIVE_SECRETS.put("qingyu.care.platform.admin-initial-password", "QINGYU_ADMIN_INITIAL_PASSWORD");
        EFFECTIVE_SECRETS.put("spring.data.redis.password", "REDIS_PASSWORD");
    }

    /** 仓库里写死过的默认口令：配置文件默认值、本地 compose 与 dev profile 用的那几个 */
    static final Set<String> KNOWN_DEFAULTS = Set.of(
            "postgres", "rustfsadmin", "rustfsadmin123", "admin123", "demo123456");

    private final Environment environment;

    public ProductionSecretsGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        // dev/test 用固定口令与本地容器，模板占位符在那里无意义；demo 是对外演示环境，照样要拦
        if (environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            return;
        }
        List<String> offending = new ArrayList<>();
        for (Map.Entry<String, String> e : EFFECTIVE_SECRETS.entrySet()) {
            String v = environment.getProperty(e.getKey());
            if (v == null || v.isBlank()) {
                continue; // 留空有各自的语义（管理员口令随机生成、Redis 无密码），不在这里判
            }
            String lower = v.toLowerCase(Locale.ROOT);
            if (lower.contains("change-me") || KNOWN_DEFAULTS.contains(lower)) {
                offending.add(e.getValue());
            }
        }
        if (!offending.isEmpty()) {
            throw new IllegalStateException("以下密钥仍是占位符或仓库里的默认值，拒绝启动："
                    + String.join("、", offending)
                    + "。请在 .env 中改成真实的随机值后重启（生成方式见部署文档）。");
        }
    }
}
