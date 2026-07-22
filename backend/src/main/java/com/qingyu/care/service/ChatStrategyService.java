package com.qingyu.care.service;

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

    /**
     * 初始化默认策略
     */
    @PostConstruct
    @Transactional
    public void init() {
        if (repository.count() == 0) {
            log.info("Initializing default chat strategies...");
            
            // 默认场景策略
            repository.save(ChatStrategy.builder()
                    .name("通用默认策略")
                    .scenario("default")
                    .topK(5)
                    .similarityThreshold(0.7)
                    .allowNoKnowledge(true)
                    .isDefault(true)
                    .enabled(true)
                    .hybridEnabled(true)
                    .keywordTopK(10)
                    .rrfK(60)
                    .build());

            // 投诉场景策略（更高的 TopK 和 阈值，不允许自由发挥）
            repository.save(ChatStrategy.builder()
                    .name("严谨投诉处理")
                    .scenario("complaint")
                    .topK(8)
                    .similarityThreshold(0.8)
                    .allowNoKnowledge(false)
                    .fallbackMessage("抱歉，我无法在知识库中找到关于该投诉的明确处理方案，已为您转接人工处理。")
                    .isDefault(true)
                    .enabled(true)
                    .hybridEnabled(true)
                    .keywordTopK(10)
                    .rrfK(60)
                    .build());

            log.info("Default chat strategies initialized");
        }
    }

    /**
     * 获取指定场景的有效策略
     * 1. 查找场景默认策略（租户自有；@PostConstruct 平台种子 tenant_id=NULL 会被租户过滤挡住）
     * 2. 回退平台默认策略（tenant_id IS NULL，原生查询绕过 Hibernate 过滤器）——闭 bug 的唯一关键
     * 3. 降级到 default 场景的默认策略
     * 4. 兜底返回运行时静态对象（与 prompt 不同，strategy 语义允许无配置时有安全默认，故不抛异常）
     */
    public ChatStrategy getActiveStrategy(String scenario) {
        return repository.findByScenarioAndEnabledTrueAndIsDefaultTrue(scenario)
                .or(() -> repository.findPlatformDefaultByScenario(scenario))
                .or(() -> repository.findByScenarioAndEnabledTrueAndIsDefaultTrue("default"))
                .orElseGet(() -> ChatStrategy.builder()
                        .name("运行时硬编码兜底")
                        .scenario("default")
                        .topK(5)
                        .similarityThreshold(0.7)
                        .allowNoKnowledge(true)
                        .build());
    }

    /**
     * 管理：获取所有策略
     */
    public List<ChatStrategy> getAllStrategies() {
        return repository.findAll();
    }

    /**
     * 管理：根据 ID 获取
     */
    public ChatStrategy getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("问答策略", id.toString()));
    }

    /**
     * 管理：保存/更新策略
     */
    @Transactional
    public ChatStrategy saveStrategy(ChatStrategy strategy) {
        // 如果设置为默认，需要取消该场景下其他策略的默认状态
        if (Boolean.TRUE.equals(strategy.getIsDefault())) {
            repository.findAll().stream()
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
        if (Boolean.TRUE.equals(strategy.getIsDefault()) && strategy.getScenario().equals("default")) {
            throw new RuntimeException("不能删除系统唯一的默认场景策略");
        }
        if (abExperimentRepository.existsByStatusAndVariants_StrategyId(ExperimentStatus.RUNNING, id)) {
            throw new BusinessException("该策略正被运行中的 A/B 实验引用，无法删除");
        }
        repository.deleteById(id);
    }
}
