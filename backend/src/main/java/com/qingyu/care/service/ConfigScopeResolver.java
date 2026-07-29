package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 配置查询作用域解析器：给出「当前生效配置」查询应使用的有效 (tenantId, appId)。
 *
 * <p>有租户上下文（普通租户 / 开放 API 鉴权后）直接沿用；平台管理员登录后
 * {@link TenantContext#getTenantId()} 恒为空，则由当前 X-App-Id 反查应用归属租户，
 * 使多级配置回退解析到「该应用真实使用的那一份配置」（与该应用租户用户所见一致），
 * 而非依赖 Hibernate @Filter（平台管理员绕过过滤器会裸扫全库致单结果查询 NonUnique）。
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
public class ConfigScopeResolver {

    private final ApplicationRepository applicationRepository;

    public Scope resolve() {
        Long appId = TenantContext.getAppId();
        Long tenantId = TenantContext.getTenantId();
        // 仅平台管理员(合法绕过 @Filter)才由应用反查归属租户；其余无租户上下文一律留空 → 交回退链
        // 落平台默认，避免异常态(已登录非管理员却缺上下文,原 @Filter 以 -1 拒绝)经反查串到他租户配置。
        if (tenantId == null && appId != null && SecurityUtil.isPlatformAdmin()) {
            tenantId = applicationRepository.findTenantIdById(appId).orElse(null);
        }
        return new Scope(tenantId, appId);
    }

    /** 有效配置作用域。tenantId 为空表示无有效租户（应跳过租户自有查询、直落平台默认）。 */
    public record Scope(Long tenantId, Long appId) {}
}
