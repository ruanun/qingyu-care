package com.qingyu.care.config;

import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.ModelProvider;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.repository.AiModelConfigRepository;
import com.qingyu.care.repository.ModelProviderRepository;
import com.qingyu.care.service.ModelProviderCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 把还没挂到供应商连接上的模型归组成连接：存量数据（连接是后加的）与首次启动的播种（ModelRouterService.init
 * 按 yml 播种模型，不感知连接）走同一条路。
 *
 * <p>归组键 = (归属租户, 厂商, 端点, Key)：同一租户、同一家、同一套凭据的模型挂到同一个连接下——租户的连接是租户级，
 * 所以不同应用里填了同一把 Key 的模型会合并到一起，这正是连接要解决的「同一把 Key 填多次」。
 * 已存在完全相同凭据的连接就复用，可重复执行。
 *
 * @author ruan
 */
@Component
@Order(-2)
@Slf4j
public class ModelProviderBackfillRunner implements ApplicationRunner {

    private final AiModelConfigRepository modelRepository;
    private final ModelProviderRepository providerRepository;
    private final TransactionTemplate tx;

    public ModelProviderBackfillRunner(AiModelConfigRepository modelRepository,
                                       ModelProviderRepository providerRepository,
                                       PlatformTransactionManager transactionManager) {
        this.modelRepository = modelRepository;
        this.providerRepository = providerRepository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    private record GroupKey(Long tenantId, String providerKey, String endpoint, String apiKey) {
    }

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(s -> {
            backfill();
            auditScopeDrift();
        });
    }

    void backfill() {
        List<AiModelConfig> orphans = modelRepository.findWithoutProvider();
        if (orphans.isEmpty()) {
            return;
        }
        Map<GroupKey, List<Long>> groups = new LinkedHashMap<>();
        for (AiModelConfig m : orphans) {
            if (m.getTenantId() == null && m.getScope() != OwnerScope.PLATFORM) {
                // 平台管理员无上下文时建出的「租户模型」：既不是平台共享也不属于任何租户，挂不到合适的连接上
                log.warn("模型 {}（{}）没有归属租户且不是平台共享模型，跳过连接回填", m.getId(), m.getName());
                continue;
            }
            // 端点能对上某家默认端点时以端点为准：厂商字段曾被写错（平台默认对话模型写死 openai、端点却是 DashScope）
            String stored = m.getProvider() == null || m.getProvider().isBlank() ? "openai" : m.getProvider().trim();
            String providerKey = ModelProviderCatalog.keyForEndpoint(m.getEndpoint()).orElse(stored);
            GroupKey key = new GroupKey(m.getTenantId(), providerKey, normalize(m.getEndpoint()), m.getApiKey());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(m.getId());
        }
        groups.forEach((key, ids) -> {
            ModelProvider provider = findSame(key).orElseGet(() -> providerRepository.save(ModelProvider.builder()
                    .tenantId(key.tenantId())
                    .scope(key.tenantId() == null ? OwnerScope.PLATFORM : OwnerScope.TENANT)
                    .name(uniqueName(key))
                    .providerKey(key.providerKey())
                    .endpoint(key.endpoint())
                    .apiKey(key.apiKey())
                    .remark("由已有模型自动归组生成")
                    .build()));
            int n = modelRepository.attachToProvider(ids, provider.getId());
            // 挂上之后让模型行的副本与连接对齐：厂商字段按端点纠正过（如 openai → dashscope），不同步的话界面徽章仍是错的
            modelRepository.syncFromProvider(provider.getId(), provider.getProviderKey(), provider.getEndpoint(), provider.getApiKey());
            log.info("模型归组到供应商连接「{}」（id={}）：{} 个", provider.getName(), provider.getId(), n);
        });
    }

    /**
     * 模型归属与所属连接对不上时报一条启动 WARN。
     *
     * <p>只在写路径之外的改动（直接改库、早期版本的行）才能造成不一致，所以<b>不自动修</b>：
     * 猜错归属会把数据发往错租户的端点，比报出来让人手动核对危险得多。
     */
    void auditScopeDrift() {
        List<AiModelConfig> drift = modelRepository.findProviderScopeDrift();
        if (drift.isEmpty()) {
            return;
        }
        log.warn("发现 {} 个模型的归属与所属供应商连接不一致（scope / tenant_id 对不上）：{}"
                        + "。运行期会按模型行自己的 scope 参与「租户自有优先」判定，请人工核对后修正",
                drift.size(), drift.stream().map(m -> m.getId() + ":" + m.getName()).toList());
    }

    private Optional<ModelProvider> findSame(GroupKey key) {
        List<ModelProvider> candidates = key.tenantId() == null
                ? providerRepository.findPlatformProviders()
                : providerRepository.findByTenantIdOrderByIdAsc(key.tenantId());
        return candidates.stream()
                .filter(p -> key.providerKey().equals(p.getProviderKey())
                        && Objects.equals(key.endpoint(), normalize(p.getEndpoint()))
                        && Objects.equals(key.apiKey(), p.getApiKey()))
                .findFirst();
    }

    /** 厂商显示名；同一归属下已有同名连接（同厂商不同 Key）时加序号。 */
    private String uniqueName(GroupKey key) {
        String base = ModelProviderCatalog.presets().stream()
                .filter(p -> p.key().equals(key.providerKey()))
                .map(ModelProviderCatalog.ProviderPreset::label)
                .findFirst().orElse(key.providerKey());
        List<ModelProvider> existing = key.tenantId() == null
                ? providerRepository.findPlatformProviders()
                : providerRepository.findByTenantIdOrderByIdAsc(key.tenantId());
        Set<String> names = new HashSet<>();
        existing.forEach(p -> names.add(p.getName()));
        if (!names.contains(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            if (!names.contains(base + " " + i)) {
                return base + " " + i;
            }
        }
    }

    private static String normalize(String endpoint) {
        return endpoint == null || endpoint.isBlank() ? null : endpoint.trim();
    }
}
