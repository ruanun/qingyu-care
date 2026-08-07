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
 * TenantContext.getAppId() 收窄（用不带 enabled 过滤的 findByAppId，管理面须见禁用库），
 * 无 appId 返空且不查库。
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
    @Mock EmbeddingModelProvider embeddingModelProvider;
    @Mock com.qingyu.care.repository.QaPairRepository qaPairRepository;
    @Mock com.qingyu.care.repository.CrawlSiteRepository crawlSiteRepository;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private KnowledgeServiceImpl service() {
        return new KnowledgeServiceImpl(knowledgeBaseRepository, documentRepository,
                vectorStore, fileService, vectorStoreRepository, embeddingModelProvider,
                qaPairRepository, crawlSiteRepository);
    }

    @Test
    void getAllKnowledgeBases_有appId_按appId收窄() {
        TenantContext.setAppId(10L);
        KnowledgeBase kb = KnowledgeBase.builder().id(1L).name("库").enabled(true).build();
        when(knowledgeBaseRepository.findByAppId(10L)).thenReturn(List.of(kb));

        List<KnowledgeBase> result = service().getAllKnowledgeBases();

        assertThat(result).containsExactly(kb);
        verify(knowledgeBaseRepository, never()).findAll();
    }

    @Test
    void getAllKnowledgeBases_有appId_已禁用知识库也在管理列表可见() {
        TenantContext.setAppId(10L);
        KnowledgeBase disabledKb = KnowledgeBase.builder().id(2L).name("已禁用库").enabled(false).build();
        when(knowledgeBaseRepository.findByAppId(10L)).thenReturn(List.of(disabledKb));

        List<KnowledgeBase> result = service().getAllKnowledgeBases();

        assertThat(result).containsExactly(disabledKb);
        verify(knowledgeBaseRepository, never()).findByAppIdAndEnabledTrue(any());
    }

    @Test
    void getAllKnowledgeBases_无appId_返空且不查库() {
        List<KnowledgeBase> result = service().getAllKnowledgeBases();

        assertThat(result).isEmpty();
        verify(knowledgeBaseRepository, never()).findByAppId(any());
        verify(knowledgeBaseRepository, never()).findAll();
    }
}
