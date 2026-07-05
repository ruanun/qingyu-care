package com.qingyu.care.service;

import com.qingyu.care.dto.QuotaCheckResult;
import com.qingyu.care.dto.QuotaViewDTO;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.repository.SysApiUsageRepository;
import com.qingyu.care.repository.TenantRepository;
import com.qingyu.care.repository.projection.QuotaUsageRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;

/**
 * 套餐额度：解析（平台配置 + 内置默认）+ 租户当月用量检查。
 * 额度=0 表示不限。检查异常 fail-open（放行），不因组件故障拦截业务。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuotaService {

    /** 内置默认额度 [calls, tokens]；平台未配置时回落。 */
    private static final Map<TenantPlan, long[]> DEFAULTS = Map.of(
            TenantPlan.FREE, new long[]{1000, 100000},
            TenantPlan.PRO, new long[]{50000, 5000000},
            TenantPlan.ENTERPRISE, new long[]{0, 0});
    private static final int DEFAULT_WARN = 80;

    private final ConfigService configService;
    private final TenantRepository tenantRepository;
    private final SysApiUsageRepository usageRepository;

    public QuotaCheckResult check(Long tenantId) {
        try {
            long[] limits = resolveQuota(tenantId);
            long callsLimit = limits[0], tokensLimit = limits[1];
            if (callsLimit <= 0 && tokensLimit <= 0) {
                return new QuotaCheckResult(false, false, 0, callsLimit, 0, tokensLimit);
            }
            QuotaUsageRow used = usageRepository.sumSince(tenantId, monthStart());
            long callsUsed = used.getCalls(), tokensUsed = used.getTokens();
            int warn = configService.getInt(OwnerScope.PLATFORM, null, "billing.quota.warn-threshold", DEFAULT_WARN);
            boolean exceeded = (callsLimit > 0 && callsUsed >= callsLimit)
                    || (tokensLimit > 0 && tokensUsed >= tokensLimit);
            boolean near = !exceeded && (
                    (callsLimit > 0 && callsUsed >= callsLimit * warn / 100.0)
                    || (tokensLimit > 0 && tokensUsed >= tokensLimit * warn / 100.0));
            return new QuotaCheckResult(exceeded, near, callsUsed, callsLimit, tokensUsed, tokensLimit);
        } catch (Exception e) {
            log.warn("额度检查异常，fail-open 放行 tenantId={}", tenantId, e);
            return new QuotaCheckResult(false, false, 0, 0, 0, 0);
        }
    }

    public QuotaViewDTO view(Long tenantId) {
        if (tenantId == null) {
            return QuotaViewDTO.empty();
        }
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) {
            return QuotaViewDTO.empty();
        }
        long[] limits = resolveQuota(tenantId);
        QuotaUsageRow used = usageRepository.sumSince(tenantId, monthStart());
        int warn = configService.getInt(OwnerScope.PLATFORM, null, "billing.quota.warn-threshold", DEFAULT_WARN);
        return new QuotaViewDTO(tenant.getPlan().name(),
                used.getCalls(), limits[0], used.getTokens(), limits[1],
                warn, monthStart().plusMonths(1));
    }

    /**
     * 解析 [callsLimit, tokensLimit]；平台配置优先，缺失回落内置默认。
     * token 额度可达十亿级，用 long 读取避免 int 截断。
     */
    private long[] resolveQuota(Long tenantId) {
        TenantPlan plan = tenantRepository.findById(tenantId)
                .map(Tenant::getPlan).orElse(TenantPlan.FREE);
        long[] def = DEFAULTS.getOrDefault(plan, DEFAULTS.get(TenantPlan.FREE));
        long calls = configService.getLong(OwnerScope.PLATFORM, null,
                "billing.quota." + plan.name() + ".calls", def[0]);
        long tokens = configService.getLong(OwnerScope.PLATFORM, null,
                "billing.quota." + plan.name() + ".tokens", def[1]);
        return new long[]{calls, tokens};
    }

    private LocalDate monthStart() {
        return LocalDate.now().withDayOfMonth(1);
    }
}
