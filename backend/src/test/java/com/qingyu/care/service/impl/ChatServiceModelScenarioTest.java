package com.qingyu.care.service.impl;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.enums.OwnerScope;
import com.qingyu.care.repository.ChatMessageRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.AbTestService;
import com.qingyu.care.service.ChatStrategyService;
import com.qingyu.care.service.ConfigService;
import com.qingyu.care.service.HandoverRuleService;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.ModelRouterService;
import com.qingyu.care.service.PromptTemplateService;
import com.qingyu.care.service.RetrievalScopeResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 默认模型场景解析单测（租户配置 tenant.default.model.scenario 接入模型路由）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceModelScenarioTest {

    @Mock VectorStore vectorStore;
    @Mock ChatSessionRepository sessionRepository;
    @Mock ChatMessageRepository messageRepository;
    @Mock PromptTemplateService promptTemplateService;
    @Mock HandoverRuleService handoverRuleService;
    @Mock ModelRouterService modelRouterService;
    @Mock ChatStrategyService chatStrategyService;
    @Mock HybridRetrievalService hybridRetrievalService;
    @Mock RetrievalScopeResolver retrievalScopeResolver;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock TransactionTemplate transactionTemplate;
    @Mock AbTestService abTestService;
    @Mock ConfigService configService;
    @InjectMocks ChatServiceImpl chatService;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void 租户配置了模型场景_解析为配置值() {
        TenantContext.setTenantId(7L);
        when(configService.get(OwnerScope.TENANT, 7L, "tenant.default.model.scenario", "default"))
                .thenReturn("faq");

        assertThat(chatService.resolveModelScenario()).isEqualTo("faq");
    }

    @Test
    void 未配置_回落default场景() {
        TenantContext.setTenantId(7L);
        when(configService.get(OwnerScope.TENANT, 7L, "tenant.default.model.scenario", "default"))
                .thenReturn("default");

        assertThat(chatService.resolveModelScenario()).isEqualTo("default");
    }

    @Test
    void 无租户上下文_以空租户查询平台无命中时回落default() {
        // 平台管理员测试对话时 TenantContext.tenantId 为 null，租户级配置查不到 → 回落 default
        when(configService.get(OwnerScope.TENANT, null, "tenant.default.model.scenario", "default"))
                .thenReturn("default");

        assertThat(chatService.resolveModelScenario()).isEqualTo("default");
    }
}
