package com.qingyu.care.service;

import com.qingyu.care.config.PlatformSeeder;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.enums.ExperimentStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.AbExperimentRepository;
import com.qingyu.care.repository.ChatStrategyRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 问答策略服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatStrategyService {

    private final ChatStrategyRepository repository;
    private final AbExperimentRepository abExperimentRepository;
    private final ConfigScopeResolver configScopeResolver;

    /**
     * 初始化默认策略
     */
    @PostConstruct
    @Transactional
    public void init() {
        PlatformSeeder.seedIfEmpty(repository.count(), "问答策略", () -> {
            // 默认场景策略
            //
            // ⚠️ similarityThreshold 是按当前 embedding 模型实测标定的，别凭直觉往上调。
            // qwen3.7-text-embedding 下的实际分数分布（2026-08-11 真机测量，中文短问句 →
            // 帮助中心长段落）：
            //   真正相关的命中  0.57 ~ 0.73（逐字命中标题的问法才 0.7298）
            //   完全无关的问题  0.25 ~ 0.30（天气 / 写诗 / 编程）
            // 原值 0.7 恰好卡在相关命中那一带的顶端，几乎把所有真实相关的匹配都滤掉，
            // 表现为「知识库明明有、问什么都答不知道」。0.45 落在 0.30 与 0.57 之间那段
            // 干净空档的中央：噪声进不来、真命中不漏掉。
            // 换 embedding 模型后这个刻度会整体平移，必须重新测量而不是沿用本值——
            // 测法见 docs/使用手册.md「问答策略」一节。
            // 默认策略「跟随向量模型」（followModelThreshold 留空即跟随）：实际生效的是应用当前向量模型的
            // 推荐阈值，0.45 只在关掉跟随时才用。换模型后不必再回来改策略。
            repository.save(ChatStrategy.builder()
                    .name("通用默认策略")
                    .scenario("default")
                    .topK(5)
                    .similarityThreshold(0.45)
                    .allowNoKnowledge(true)
                    .isDefault(true)
                    .enabled(true)
                    .hybridEnabled(true)
                    .keywordTopK(10)
                    .rrfK(60)
                    .build());

            // 投诉场景策略（更高的 TopK 和阈值，不允许自由发挥）
            //
            // 阈值保持「比默认高 0.1」这个相对关系（0.45 + 0.1）：本场景的意图是宁可转人工
            // 也不猜，所以要比默认严一档。原值 0.8 是在旧刻度下的 0.7 + 0.1，重标定后照搬
            // 会变成「任何问题都够不着、永远转人工」——严到失去意义。
            // 本场景要「比默认严一档」，所以不跟随向量模型、固定用自己的值；换向量模型时界面会点名提醒它。
            repository.save(ChatStrategy.builder()
                    .name("严谨投诉处理")
                    .scenario("complaint")
                    .topK(8)
                    .similarityThreshold(0.55)
                    .followModelThreshold(false)
                    .allowNoKnowledge(false)
                    .fallbackMessage("抱歉，我无法在知识库中找到关于该投诉的明确处理方案，已为您转接人工处理。")
                    .isDefault(true)
                    .enabled(true)
                    .hybridEnabled(true)
                    .keywordTopK(10)
                    .rrfK(60)
                    .build());
        });
    }

    /**
     * 获取指定场景的有效策略
     * 显式按有效作用域收窄，避免平台管理员绕过 @Filter 时 NonUnique。
     * 1. 按 {@link ConfigScopeResolver} 解析出的有效 (tenantId, appId) 查场景默认策略（租户自有）
     * 2. 回退平台默认策略（tenant_id IS NULL，原生查询绕过 Hibernate 过滤器）——闭 bug 的唯一关键
     * 3. 降级到 default 场景的默认策略（同样按有效作用域收窄）
     * 4. 兜底返回运行时静态对象（与 prompt 不同，strategy 语义允许无配置时有安全默认，故不抛异常）
     */
    public ChatStrategy getActiveStrategy(String scenario) {
        ConfigScopeResolver.Scope scope = configScopeResolver.resolve();
        return findScopedActiveDefault(scenario, scope)
                .or(() -> repository.findPlatformDefaultByScenario(scenario))
                .or(() -> findScopedActiveDefault("default", scope))
                .orElseGet(() -> ChatStrategy.builder()
                        .name("运行时硬编码兜底")
                        .scenario("default")
                        .topK(5)
                        .similarityThreshold(0.45)   // 与 init() 播种的平台默认保持一致
                        .allowNoKnowledge(true)
                        .build());
    }

    /** 按有效作用域取租户自有场景默认策略；无有效租户(平台管理员且应用无归属)则跳过、交由平台默认兜底。 */
    private Optional<ChatStrategy> findScopedActiveDefault(String scenario, ConfigScopeResolver.Scope scope) {
        if (scope.tenantId() == null) {
            return Optional.empty();
        }
        return repository.findActiveDefaultScoped(scenario, scope.tenantId(), scope.appId())
                .stream().findFirst();
    }

    /**
     * 管理：获取所有策略——租户自有(按 tenantId) ∪ 平台默认(tenant_id IS NULL)，按 id 去重。
     * TenantDataAspect 对 PLATFORM_ADMIN 整体不启用 @Filter，禁止裸 findAll；无 tenantId
     * （平台管理员）时自有部分为空，但平台默认策略仍须可见——保住多级回退可管理性。
     */
    public List<ChatStrategy> getAllStrategies() {
        Long tenantId = TenantContext.getTenantId();
        List<ChatStrategy> own = tenantId == null ? List.of() : repository.findByTenantId(tenantId);
        List<ChatStrategy> platformDefaults = repository.findAllPlatformDefaults();
        return mergeById(own, platformDefaults);
    }

    private List<ChatStrategy> mergeById(List<ChatStrategy> own, List<ChatStrategy> platformDefaults) {
        java.util.LinkedHashMap<Long, ChatStrategy> byId = new java.util.LinkedHashMap<>();
        for (ChatStrategy s : own) byId.putIfAbsent(s.getId(), s);
        for (ChatStrategy s : platformDefaults) byId.putIfAbsent(s.getId(), s);
        return new java.util.ArrayList<>(byId.values());
    }

    /**
     * 管理：根据 ID 获取
     */
    public ChatStrategy getById(Long id) {
        ChatStrategy strategy = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("问答策略", id.toString()));
        // findById 不受 @Filter 保护，显式校验归属，不匹配按「不存在」同语义处理防枚举探测
        if (!TenantOwnershipGuard.belongsToCurrentContext(strategy.getTenantId(), strategy.getAppId())) {
            throw new ResourceNotFoundException("问答策略", id.toString());
        }
        return strategy;
    }

    /**
     * 管理：保存/更新策略
     */
    @Transactional
    public ChatStrategy saveStrategy(ChatStrategy strategy) {
        // 写路径守卫：平台默认策略（tenant_id IS NULL 种子行）仅平台管理员可改，
        // 否则租户可枚举 id 篡改平台默认，殃及全部回退到它的租户
        if (strategy.getId() != null
                && !TenantOwnershipGuard.mutableInCurrentContext(strategy.getTenantId(), strategy.getAppId())) {
            throw new BusinessException(403, "平台默认策略仅平台管理员可修改");
        }
        // 如果设置为默认，需要取消该场景下其他策略的默认状态。
        // 平台管理员不受租户过滤器约束，findAll 会看到全库策略——必须按租户维度再过滤一次，
        // 否则平台管理员的保存会把其他租户同场景策略的默认位也清掉。
        // 范围锚定被保存策略自身的租户：更新取行上的 tenantId；新建时实体尚未回填，取上下文
        if (Boolean.TRUE.equals(strategy.getIsDefault())) {
            Long scopeTenantId = strategy.getId() != null ? strategy.getTenantId() : TenantContext.getTenantId();
            repository.findAll().stream()
                    .filter(s -> Objects.equals(s.getTenantId(), scopeTenantId))
                    .filter(s -> s.getScenario().equals(strategy.getScenario()) && !s.getId().equals(strategy.getId()))
                    .forEach(s -> {
                        s.setIsDefault(false);
                        repository.save(s);
                    });
        }
        return repository.save(strategy);
    }

    /**
     * 管理：删除策略
     */
    @Transactional
    public void deleteStrategy(Long id) {
        ChatStrategy strategy = getById(id);
        // 写路径守卫：平台默认策略仅平台管理员可删（读放行是为保多级回退，删除必须收紧）
        if (!TenantOwnershipGuard.mutableInCurrentContext(strategy.getTenantId(), strategy.getAppId())) {
            throw new BusinessException(403, "平台默认策略仅平台管理员可删除");
        }
        if (Boolean.TRUE.equals(strategy.getIsDefault()) && strategy.getScenario().equals("default")) {
            throw new RuntimeException("不能删除系统唯一的默认场景策略");
        }
        if (abExperimentRepository.existsByStatusAndVariants_StrategyId(ExperimentStatus.RUNNING, id)) {
            throw new BusinessException("该策略正被运行中的 A/B 实验引用，无法删除");
        }
        repository.deleteById(id);
    }
}
