package com.qingyu.care.service;

import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.exception.ResourceNotFoundException;
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
                    .build());

            log.info("Default chat strategies initialized");
        }
    }

    /**
     * 获取指定场景的有效策略
     * 1. 查找场景默认策略
     * 2. 降级到 default 场景的默认策略
     * 3. 兜底返回静态对象
     */
    public ChatStrategy getActiveStrategy(String scenario) {
        return repository.findByScenarioAndEnabledTrueAndIsDefaultTrue(scenario)
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
        repository.deleteById(id);
    }
}
