package com.qingyu.care.controller;

import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.ChatStrategyUpdateRequest;
import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.service.ChatStrategyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 问答策略管理 API
 */
@RestController
@RequestMapping("/v1/strategies")
@RequiredArgsConstructor
public class ChatStrategyController {

    private final ChatStrategyService strategyService;

    @GetMapping
    public ApiResponse<List<ChatStrategy>> getAll() {
        return ApiResponse.success(strategyService.getAllStrategies());
    }

    @GetMapping("/{id}")
    public ApiResponse<ChatStrategy> getById(@PathVariable Long id) {
        return ApiResponse.success(strategyService.getById(id));
    }

    @PostMapping
    public ApiResponse<ChatStrategy> create(@RequestBody ChatStrategy strategy) {
        strategy.setId(null);
        return ApiResponse.success("策略已创建", strategyService.saveStrategy(strategy));
    }

    @PutMapping("/{id}")
    public ApiResponse<ChatStrategy> update(@PathVariable Long id, @RequestBody ChatStrategyUpdateRequest strategy) {
        ChatStrategy existing = strategyService.getById(id);
        
        if (strategy.getName() != null) existing.setName(strategy.getName());
        if (strategy.getScenario() != null) existing.setScenario(strategy.getScenario());
        if (strategy.getTopK() != null) existing.setTopK(strategy.getTopK());
        if (strategy.getSimilarityThreshold() != null) existing.setSimilarityThreshold(strategy.getSimilarityThreshold());
        if (strategy.getAllowNoKnowledge() != null) existing.setAllowNoKnowledge(strategy.getAllowNoKnowledge());
        if (strategy.getFallbackMessage() != null) existing.setFallbackMessage(strategy.getFallbackMessage());
        if (strategy.getPromptTemplateId() != null) existing.setPromptTemplateId(strategy.getPromptTemplateId());
        if (strategy.getEnabled() != null) existing.setEnabled(strategy.getEnabled());
        if (strategy.getIsDefault() != null) existing.setIsDefault(strategy.getIsDefault());
        
        return ApiResponse.success("策略已更新", strategyService.saveStrategy(existing));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        strategyService.deleteStrategy(id);
        return ApiResponse.success("策略已删除", null);
    }
}
