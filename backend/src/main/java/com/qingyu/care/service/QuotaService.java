package com.qingyu.care.service;

import com.qingyu.care.dto.QuotaCheckResult;
import com.qingyu.care.dto.QuotaViewDTO;
import com.qingyu.care.entity.Tenant;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.enums.TenantPlan;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.StorageUsageDao;
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

    private static final long GB = 1024L * 1024 * 1024;

    /**
     * 内置默认存储额度（字节），0 = 不限。对象存储本身便宜，这个配额的目的是<b>防滥用</b>而非控成本
     * ——真正贵的是 embedding，那个由 token 额度管。单个知识库文件 30MB、聊天图片 5MB，
     * 1GB 大约能放几百份文档或两百来张图，够 FREE 试用，也足以挡住「不断建会话灌满附件」那条路径。
     */
    private static final Map<TenantPlan, Long> DEFAULT_STORAGE = Map.of(
            TenantPlan.FREE, GB,
            TenantPlan.PRO, 20 * GB,
            TenantPlan.ENTERPRISE, 0L);

    private final ConfigService configService;
    private final TenantRepository tenantRepository;
    private final SysApiUsageRepository usageRepository;
    private final StorageUsageDao storageUsageDao;

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
                currentStorageBytes(tenantId), resolveStorageLimit(tenantId),
                warn, monthStart().plusMonths(1));
    }

    /**
     * 解析该租户的存储上限（字节），0 = 不限。平台配置优先，缺失回落内置默认。
     */
    public long resolveStorageLimit(Long tenantId) {
        TenantPlan plan = tenantRepository.findById(tenantId)
                .map(Tenant::getPlan).orElse(TenantPlan.FREE);
        long def = DEFAULT_STORAGE.getOrDefault(plan, DEFAULT_STORAGE.get(TenantPlan.FREE));
        return configService.getLong(OwnerScope.PLATFORM, null,
                "billing.quota." + plan.name() + ".storage-bytes", def);
    }

    /** 租户当前已占用的对象存储字节数；无租户上下文或统计失败返回 0。 */
    public long currentStorageBytes(Long tenantId) {
        if (tenantId == null) {
            return 0L;
        }
        try {
            return storageUsageDao.sumBytes(tenantId);
        } catch (Exception e) {
            log.warn("统计存储用量失败 tenantId={}", tenantId, e);
            return 0L;
        }
    }

    /**
     * 上传前的存储配额闸门：已用 + 本次 &gt; 上限则拒绝（安全审查 M5 的残留部分）。
     *
     * <p>只管对象存储（知识库文件 + 聊天附件），口径见 {@link StorageUsageDao}。
     * 无租户上下文（平台管理员）或上限为 0（不限）时直接放行，后者还会省掉那次统计查询。
     *
     * <p>fail-open：统计组件故障时放行并记日志，与 {@link #check} 同惯例——不因为算不出用量
     * 就挡住正常业务。
     *
     * @throws BusinessException 超出配额，消息可直接给用户看
     */
    public void requireStorageAvailable(Long tenantId, long incomingBytes) {
        if (tenantId == null) {
            return;
        }
        long limit = resolveStorageLimit(tenantId);
        if (limit <= 0) {
            return;
        }
        long used;
        try {
            used = storageUsageDao.sumBytes(tenantId);
        } catch (Exception e) {
            log.warn("存储配额检查异常，fail-open 放行 tenantId={}", tenantId, e);
            return;
        }
        if (used + incomingBytes > limit) {
            throw new BusinessException("存储空间不足：已用 " + toMb(used) + "MB / 上限 "
                    + toMb(limit) + "MB，请先删除不用的文档或附件，或升级套餐");
        }
    }

    private static long toMb(long bytes) {
        return bytes / 1024 / 1024;
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
