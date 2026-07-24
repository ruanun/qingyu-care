package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import com.qingyu.care.service.impl.KnowledgeServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBase 管理面 list 的应用级收窄单测：TenantDataAspect 对 PLATFORM_ADMIN 整体不启用
 * @Filter，裸 findAll 会让平台管理员跨应用读全平台知识库；getAllKnowledgeBases 须显式按
 * TenantContext.getAppId() 收窄（复用既有 findByAppIdAndEnabledTrue），无 appId 返空且不查库。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseListScopeTest {

    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock DocumentRepository documentRepository;
    @Mock VectorStore vectorStore;
    @Mock FileService fileService;
    @Mock VectorStoreRepository vectorStoreRepository;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private KnowledgeServiceImpl service() {
        return new KnowledgeServiceImpl(knowledgeBaseRepository, documentRepository,
                vectorStore, fileService, vectorStoreRepository);
    }

    @Test
    void getAllKnowledgeBases_有appId_按appId收窄() {
        TenantContext.setAppId(10L);
        KnowledgeBase kb = KnowledgeBase.builder().id(1L).name("库").enabled(true).build();
        when(knowledgeBaseRepository.findByAppIdAndEnabledTrue(10L)).thenReturn(List.of(kb));

        List<KnowledgeBase> result = service().getAllKnowledgeBases();

        assertThat(result).containsExactly(kb);
        verify(knowledgeBaseRepository, never()).findAll();
    }

    @Test
    void getAllKnowledgeBases_无appId_返空且不查库() {
        List<KnowledgeBase> result = service().getAllKnowledgeBases();

        assertThat(result).isEmpty();
        verify(knowledgeBaseRepository, never()).findByAppIdAndEnabledTrue(any());
        verify(knowledgeBaseRepository, never()).findAll();
    }
}
