package com.qingyu.care.service.provider;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.dto.ModelProviderUpdateRequest;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.ModelProvider;
import com.qingyu.care.enums.ModelType;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.AiModelConfigRepository;
import com.qingyu.care.repository.ModelProviderRepository;
import com.qingyu.care.service.ConfigScopeResolver;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.embedding.EmbeddingModelAdminService;
import com.qingyu.care.service.embedding.EmbeddingModelProbe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 供应商连接的管理：建 / 改 / 删连接、拉远端模型列表、在连接下批量添加模型。
 *
 * <p>两种归属走同一套逻辑，用 {@link OwnerScope} 区分：
 * <ul>
 *   <li>TENANT：租户级连接（同租户各应用共用），模型加到<b>当前应用</b>；</li>
 *   <li>PLATFORM：平台连接（tenant_id IS NULL），只有平台管理员能在下面挂模型——租户不能拿平台的 Key 调任意模型。</li>
 * </ul>
 * 控制器负责鉴权（租户管理员 / 平台管理员），这里负责<b>归属</b>：按 id 取连接时一律显式校验，不存在与无权同一个 404。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelProviderService {

    private final ModelProviderRepository providerRepository;
    private final AiModelConfigRepository modelRepository;
    private final ModelRouterService modelRouterService;
    private final EmbeddingModelAdminService embeddingModelAdminService;
    private final EmbeddingModelProbe embeddingModelProbe;
    private final RemoteModelLister remoteModelLister;
    private final ConfigScopeResolver configScopeResolver;

    /** 连接 + 其下模型（租户侧只含当前应用的模型）。 */
    public record ProviderWithModels(ModelProvider provider, List<AiModelConfig> models) {
    }

    /** 批量添加的一项。name 为空时用模型 ID。 */
    public record NewModel(String modelId, ModelType modelType, String name) {
    }

    /** 批量添加的单项结果：向量模型探测失败等只影响这一项。 */
    public record AddResult(String modelId, boolean ok, String message, Long id) {
    }

    // ===== 查询 =====

    public List<ProviderWithModels> list(OwnerScope scope) {
        List<ModelProvider> providers = scope == OwnerScope.PLATFORM
                ? providerRepository.findPlatformProviders()
                : providerRepository.findByTenantIdOrderByIdAsc(requireTenantId());
        Long appId = TenantContext.getAppId();
        List<ProviderWithModels> out = new ArrayList<>();
        for (ModelProvider p : providers) {
            List<AiModelConfig> models;
            if (scope == OwnerScope.PLATFORM) {
                models = modelRepository.findByProviderIdAcrossApps(p.getId());
            } else {
                models = appId == null ? List.of() : modelRepository.findByProviderIdAndAppId(p.getId(), appId);
            }
            out.add(new ProviderWithModels(p, models));
        }
        return out;
    }

    /** 按 id 取连接并校验归属：租户侧只能取本租户的，平台侧只能取平台的。 */
    public ModelProvider requireOwned(Long id, OwnerScope scope) {
        ModelProvider p = providerRepository.findById(id).orElse(null);
        boolean owned = p != null && (scope == OwnerScope.PLATFORM
                ? p.getTenantId() == null && p.getScope() == OwnerScope.PLATFORM
                : p.getTenantId() != null && p.getTenantId().equals(requireTenantId()));
        if (!owned) {
            throw new ResourceNotFoundException("供应商连接", String.valueOf(id));
        }
        return p;
    }

    // ===== 连接的增删改 =====

    @Transactional
    public ModelProvider create(ModelProvider req, OwnerScope scope) {
        requireText(req.getName(), "名称");
        requireText(req.getProviderKey(), "厂商");
        modelRouterService.requireValidEndpoint(req.getEndpoint());
        ModelProvider p = ModelProvider.builder()
                .name(req.getName().trim())
                .providerKey(req.getProviderKey().trim())
                .endpoint(blankToNull(req.getEndpoint()))
                .apiKey(blankToNull(req.getApiKey()))
                .remark(req.getRemark())
                .scope(scope)
                .tenantId(scope == OwnerScope.PLATFORM ? null : requireTenantId())
                .build();
        return providerRepository.save(p);
    }

    /**
     * 改连接。端点或 Key 变了就把新凭据同步到其下全部模型（跨应用），并先用新凭据把其下的向量模型逐个探测一遍——
     * 换了 Key 或端点，输出维度就可能变；对不上的话那些应用的摄取会全部失败，必须在保存前拦下。
     */
    @Transactional
    public ModelProvider update(Long id, ModelProviderUpdateRequest req, OwnerScope scope) {
        ModelProvider p = requireOwned(id, scope);
        if (req.getEndpoint() != null) {
            modelRouterService.requireValidEndpoint(req.getEndpoint());
        }
        String newEndpoint = req.getEndpoint() != null ? blankToNull(req.getEndpoint()) : p.getEndpoint();
        // 凭据额外判 isBlank：编辑弹窗不回显 Key，空串或全空格都表示「不修改」
        String newKey = req.getApiKey() != null && !req.getApiKey().isBlank() ? req.getApiKey().trim() : p.getApiKey();
        String newProviderKey = req.getProviderKey() != null && !req.getProviderKey().isBlank()
                ? req.getProviderKey().trim() : p.getProviderKey();
        boolean credentialChanged = !Objects.equals(newEndpoint, p.getEndpoint()) || !Objects.equals(newKey, p.getApiKey())
                || !Objects.equals(newProviderKey, p.getProviderKey());

        if (credentialChanged) {
            for (AiModelConfig m : modelRepository.findByProviderIdAcrossApps(p.getId())) {
                if (ModelType.orDefault(m.getModelType()) != ModelType.EMBEDDING) {
                    continue;
                }
                AiModelConfig candidate = AiModelConfig.builder().provider(newProviderKey).modelId(m.getModelId())
                        .endpoint(newEndpoint).apiKey(newKey).modelType(ModelType.EMBEDDING).build();
                try {
                    embeddingModelProbe.requireCompatible(candidate);
                } catch (InvalidOperationException e) {
                    throw new InvalidOperationException("向量模型「" + m.getName() + "」用新的端点或 Key 校验失败：" + e.getMessage());
                }
            }
        }
        if (req.getName() != null && !req.getName().isBlank()) p.setName(req.getName().trim());
        if (req.getRemark() != null) p.setRemark(req.getRemark());
        p.setEndpoint(newEndpoint);
        p.setApiKey(newKey);
        p.setProviderKey(newProviderKey);
        ModelProvider saved = providerRepository.save(p);
        if (credentialChanged) {
            int n = modelRepository.syncFromProvider(saved.getId(), saved.getProviderKey(), saved.getEndpoint(), saved.getApiKey());
            log.info("供应商连接 {} 的凭据已更新，同步到 {} 个模型", saved.getId(), n);
        }
        return saved;
    }

    /** 删连接：其下还有模型（含其他应用的）就拒绝——模型的凭据副本会变成无主的孤儿。 */
    @Transactional
    public void delete(Long id, OwnerScope scope) {
        ModelProvider p = requireOwned(id, scope);
        int n = modelRepository.findByProviderIdAcrossApps(p.getId()).size();
        if (n > 0) {
            throw new InvalidOperationException("该连接下还有 " + n + " 个模型（可能包括其他应用的），请先删除这些模型");
        }
        providerRepository.delete(p);
    }

    // ===== 远端模型 =====

    /** 拉远端模型列表，同时就是「测试连接」。 */
    public List<RemoteModelLister.RemoteModel> remoteModels(Long id, OwnerScope scope) {
        return remoteModelLister.list(requireOwned(id, scope));
    }

    // ===== 在连接下添加模型 =====

    /**
     * 批量添加。每项单独成败：向量模型会真实探测维度，一项不过不影响其他项。
     * 同一连接下（租户侧：同一应用）已有同 ID 同类型的模型则跳过。
     */
    public List<AddResult> addModels(Long providerId, List<NewModel> items, OwnerScope scope) {
        ModelProvider p = requireOwned(providerId, scope);
        if (scope == OwnerScope.TENANT && TenantContext.getAppId() == null) {
            throw new InvalidOperationException("请先选择应用");
        }
        List<AiModelConfig> existing = scope == OwnerScope.PLATFORM
                ? modelRepository.findByProviderIdAcrossApps(p.getId())
                : modelRepository.findByProviderIdAndAppId(p.getId(), TenantContext.getAppId());
        List<AddResult> results = new ArrayList<>();
        for (NewModel item : items) {
            String modelId = item.modelId() == null ? "" : item.modelId().trim();
            if (modelId.isEmpty()) {
                continue;
            }
            ModelType type = ModelType.orDefault(item.modelType());
            boolean dup = existing.stream().anyMatch(m -> modelId.equals(m.getModelId())
                    && ModelType.orDefault(m.getModelType()) == type);
            if (dup) {
                results.add(new AddResult(modelId, false, "已存在，跳过", null));
                continue;
            }
            AiModelConfig config = AiModelConfig.builder()
                    .name(item.name() != null && !item.name().isBlank() ? item.name().trim() : modelId)
                    .modelId(modelId)
                    .modelType(type)
                    .build();
            try {
                AiModelConfig saved = createModel(config, p, scope);
                existing = append(existing, saved);
                results.add(new AddResult(modelId, true, null, saved.getId()));
            } catch (InvalidOperationException e) {
                results.add(new AddResult(modelId, false, e.getMessage(), null));
            }
        }
        return results;
    }

    /**
     * 在连接下建一个模型：凭据从连接复制，归属按 scope 强制（租户侧由 TenantEntityListener 填当前租户 / 应用）。
     * 单个创建的旧接口（POST /v1/ai-models、/v1/platform/models）也走这里。
     */
    @Transactional
    public AiModelConfig createModel(AiModelConfig config, ModelProvider provider, OwnerScope scope) {
        config.setId(null);
        config.setProviderId(provider.getId());
        config.setProvider(provider.getProviderKey());
        config.setEndpoint(provider.getEndpoint());
        config.setApiKey(provider.getApiKey());
        if (config.getScenario() == null) config.setScenario("default");
        if (config.getPriority() == null) config.setPriority(100);
        if (config.getEnabled() == null) config.setEnabled(true);
        embeddingModelAdminService.prepareCreate(config);
        if (scope == OwnerScope.PLATFORM) {
            return modelRouterService.saveSharedModel(config);
        }
        config.setScope(OwnerScope.TENANT);
        return modelRouterService.saveModel(config);
    }

    // ===== 工具 =====

    /**
     * 租户侧的有效租户：普通租户取会话里的；平台管理员按当前应用反查（同 ConfigScopeResolver）。
     * 取不到就拒绝——否则建出来的连接 tenant_id 为 NULL，等于凭空多出一个「平台连接」。
     */
    private Long requireTenantId() {
        Long tenantId = configScopeResolver.resolve().tenantId();
        if (tenantId == null) {
            throw new InvalidOperationException("无法确定所属租户，请先选择应用");
        }
        return tenantId;
    }

    private static List<AiModelConfig> append(List<AiModelConfig> list, AiModelConfig m) {
        List<AiModelConfig> copy = new ArrayList<>(list);
        copy.add(m);
        return copy;
    }

    private static void requireText(String s, String label) {
        if (s == null || s.isBlank()) {
            throw new InvalidOperationException(label + "不能为空");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
