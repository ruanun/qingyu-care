package com.qingyu.care.service.embedding;

import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.enums.ModelType;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.repository.AiModelConfigRepository;
import com.qingyu.care.repository.ApplicationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析「某个应用该用哪个向量模型」。全仓唯一入口——写入、检索、迁移都从这里拿模型，别各自去读指针。
 *
 * <p>⚠️ <b>不依赖 TenantContext</b>：摄取与迁移跑在 {@code @Async} 线程里，appId 一律来自数据行自身
 * （文档 / QA / 知识库的 app_id），指针与模型行都按 id 原生查询读取。应用钉的往往是平台共享模型
 * （tenant_id IS NULL），在租户过滤器下根本看不到——这正是「多级配置回退」那个老坑，这里从一开始就绕开。
 * 也因此这里<b>从不接受客户端传来的模型 id</b>：那种 id 必须先过 {@link #requireSelectable}。
 *
 * @author ruan
 */
@Service
@Slf4j
public class EmbeddingModelResolver {

    private final ApplicationRepository applicationRepository;
    private final AiModelConfigRepository modelRepository;
    private final TransactionTemplate requiresNew;

    public EmbeddingModelResolver(ApplicationRepository applicationRepository,
                                  AiModelConfigRepository modelRepository,
                                  PlatformTransactionManager transactionManager) {
        this.applicationRepository = applicationRepository;
        this.modelRepository = modelRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        // 钉默认模型是一次写：检索跑在只读事务里，直接在当前事务里 UPDATE 会被 PG 拒（read-only transaction）
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 应用当前生效的向量模型。还没钉过（老应用 / 刚建的应用）就钉成平台默认。
     *
     * @throws InvalidOperationException 应用不存在，或平台没有任何可用的向量模型
     */
    public AiModelConfig active(Long appId) {
        ApplicationRepository.EmbeddingPointers pointers = pointers(appId);
        Long activeId = pointers.getActive();
        if (activeId == null) {
            activeId = pinPlatformDefault(appId);
        }
        return load(activeId);
    }

    /**
     * 写入目标：当前模型，外加切换进行中时的目标模型。
     *
     * <p>⚠️ 影子重建期间新上传 / 重新处理的内容必须<b>两边都写</b>：迁移任务只处理它开始时已存在的内容，
     * 只写当前模型的话，这批内容切过去之后在新模型下就不存在了（迁移收尾的补漏扫描是第二道保险）。
     */
    public List<AiModelConfig> writeTargets(Long appId) {
        ApplicationRepository.EmbeddingPointers pointers = pointers(appId);
        List<AiModelConfig> targets = new ArrayList<>(2);
        targets.add(pointers.getActive() != null ? load(pointers.getActive()) : active(appId));
        if (pointers.getPending() != null && !pointers.getPending().equals(targets.get(0).getId())) {
            AiModelConfig pending = load(pointers.getPending());
            // 同标签的两个模型写出来的向量完全一样，写一份就够
            if (!EmbeddingTag.of(pending).equals(EmbeddingTag.of(targets.get(0)))) {
                targets.add(pending);
            }
        }
        return targets;
    }

    /** 切换进行中的目标模型；没有则为 null。 */
    public AiModelConfig pending(Long appId) {
        Long pendingId = pointers(appId).getPending();
        return pendingId == null ? null : load(pendingId);
    }

    /**
     * 本应用可选的向量模型：应用自有 ∪ 平台共享（均为启用的 EMBEDDING），自有在前。请求线程调用。
     */
    public List<AiModelConfig> selectable(Long appId) {
        Map<Long, AiModelConfig> byId = new LinkedHashMap<>();
        for (AiModelConfig m : modelRepository.findAppEmbeddingModels(appId)) {
            byId.putIfAbsent(m.getId(), m);
        }
        for (AiModelConfig m : modelRepository.findSharedEmbeddingModels()) {
            byId.putIfAbsent(m.getId(), m);
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * 校验客户端选的模型确实对本应用可选。不可选与不存在同一个 404 口径，不泄露别人的模型 id 是否存在。
     *
     * <p>⚠️ 少了这道就是跨租户借 Key：把应用指向别的应用自有的向量模型，摄取与检索就拿着别人的 Key 出站。
     */
    public AiModelConfig requireSelectable(Long appId, Long modelId) {
        return selectable(appId).stream()
                .filter(m -> m.getId().equals(modelId))
                .findFirst()
                .orElseThrow(() -> new com.qingyu.care.exception.ResourceNotFoundException("向量模型", String.valueOf(modelId)));
    }

    /** 平台默认向量模型（启用的平台共享向量模型里优先级最高的一条）；没有则为 null。 */
    public AiModelConfig platformDefault() {
        List<AiModelConfig> shared = modelRepository.findSharedEmbeddingModels();
        return shared.isEmpty() ? null : shared.get(0);
    }

    private Long pinPlatformDefault(Long appId) {
        AiModelConfig def = platformDefault();
        if (def == null) {
            throw new InvalidOperationException("平台尚未配置可用的向量模型，请联系平台管理员在「共享模型」中添加");
        }
        requiresNew.executeWithoutResult(s -> applicationRepository.pinEmbeddingModelIfAbsent(appId, def.getId()));
        // 并发首用时可能被别的线程抢先钉了另一条（平台默认恰好在这一瞬间变了），以库里为准
        Long pinned = pointers(appId).getActive();
        log.info("应用 {} 的向量模型钉为 {}（id={}）", appId, def.getModelId(), pinned);
        return pinned != null ? pinned : def.getId();
    }

    private ApplicationRepository.EmbeddingPointers pointers(Long appId) {
        if (appId == null) {
            throw new InvalidOperationException("缺少应用上下文，无法确定向量模型");
        }
        return applicationRepository.findEmbeddingPointers(appId)
                .orElseThrow(() -> new InvalidOperationException("应用不存在：" + appId));
    }

    private AiModelConfig load(Long modelId) {
        AiModelConfig m = modelRepository.findByIdAcrossTenants(modelId)
                .orElseThrow(() -> new InvalidOperationException("应用引用的向量模型已不存在（id=" + modelId + "）"));
        if (ModelType.orDefault(m.getModelType()) != ModelType.EMBEDDING) {
            // 不该发生：切换入口只接受 EMBEDDING，模型类型又不可改。真发生了宁可报错，也别拿对话模型去打 embeddings
            throw new InvalidOperationException("应用引用的模型不是向量模型（id=" + modelId + "）");
        }
        return m;
    }
}
