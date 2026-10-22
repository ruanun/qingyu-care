package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrievalScopeResolverTest {

    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @InjectMocks RetrievalScopeResolver resolver;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void 无应用上下文_空范围且不查库() {
        TenantContext.setTenantId(1L);   // 只有租户、无应用

        RetrievalScope scope = resolver.resolve();

        assertThat(scope.isEmpty()).isTrue();
        verifyNoInteractions(knowledgeBaseRepository);
    }

    @Test
    void 正常_取当前应用enabled库id集合() {
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(2L);
        when(knowledgeBaseRepository.findByAppIdAndEnabledTrue(2L))
                .thenReturn(List.of(
                        KnowledgeBase.builder().id(10L).name("库A").enabled(true).build(),
                        KnowledgeBase.builder().id(11L).name("库B").enabled(true).build()));

        RetrievalScope scope = resolver.resolve();

        assertThat(scope.isEmpty()).isFalse();
        assertThat(scope.tenantId()).isEqualTo(1L);
        assertThat(scope.appId()).isEqualTo(2L);
        assertThat(scope.kbIds()).containsExactly(10L, 11L);
        assertThat(scope.accessLevel()).isEqualTo(DocumentVisibility.PUBLIC);   // 未设上下文 → 兜底 PUBLIC
    }

    @Test
    void 上下文有访问级别_原样传导() {
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(2L);
        TenantContext.setAccessLevel(DocumentVisibility.INTERNAL);
        when(knowledgeBaseRepository.findByAppIdAndEnabledTrue(2L))
                .thenReturn(List.of(KnowledgeBase.builder().id(10L).name("库A").enabled(true).build()));

        assertThat(resolver.resolve().accessLevel()).isEqualTo(DocumentVisibility.INTERNAL);
    }

    @Test
    void 无enabled库_空范围() {
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(2L);
        when(knowledgeBaseRepository.findByAppIdAndEnabledTrue(2L)).thenReturn(List.of());

        assertThat(resolver.resolve().isEmpty()).isTrue();
    }

    @Test
    void 查询异常_failSoft空范围() {
        TenantContext.setTenantId(1L);
        TenantContext.setAppId(2L);
        when(knowledgeBaseRepository.findByAppIdAndEnabledTrue(2L))
                .thenThrow(new RuntimeException("db down"));

        assertThat(resolver.resolve().isEmpty()).isTrue();
    }
}
