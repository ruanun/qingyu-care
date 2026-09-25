package com.qingyu.care.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /** 与 .env.example 一一对应；新增密钥类变量时同步加在这里 */
    static final List<String> SECRET_VARS = List.of(
            "SPRING_DATASOURCE_PASSWORD",
            "STORAGE_SECRET_KEY",
            "QINGYU_ADMIN_INITIAL_PASSWORD",
            "REDIS_PASSWORD");

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
        for (String name : SECRET_VARS) {
            String v = environment.getProperty(name);
            if (v != null && v.toLowerCase(Locale.ROOT).contains("change-me")) {
                offending.add(name);
            }
        }
        if (!offending.isEmpty()) {
            throw new IllegalStateException("以下环境变量仍是 .env.example 里的占位符，拒绝启动："
                    + String.join("、", offending)
                    + "。请在 .env 中改成真实的随机值后重启（生成方式见部署文档）。");
        }
    }
}
