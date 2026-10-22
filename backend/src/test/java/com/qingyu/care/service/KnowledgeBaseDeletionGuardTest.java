package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.CrawlSiteRepository;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.QaPairRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import com.qingyu.care.service.impl.KnowledgeServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import com.qingyu.care.service.embedding.KnowledgeVectorWriter;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 守护「知识库还有内容时拒绝删除，并说清各类内容各有多少」。
 *
 * <p>此前 {@code deleteKnowledgeBase} 只做 {@code delete(kb)}，而 {@code document} /
 * {@code qa_pair} / {@code crawl_site} 三张表都有指向 {@code knowledge_base} 的外键——
 * 有内容时数据库直接抛 {@code DataIntegrityViolationException}，用户拿到不可读的 500。
 *
 * <p>⚠️ 三类内容各有一条用例，不是重复：占用检查要三个 count 都查才完整，
 * 只查文档的实现能过「有文档」那条却放行「只有爬取站点」的库，仍旧 500。
 * 删掉任意一个 count 都必须有对应用例转红——这是本测试类存在的理由。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeBaseDeletionGuardTest {

    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock DocumentRepository documentRepository;
    @Mock KnowledgeVectorWriter vectorWriter;
    @Mock FileService fileService;
    @Mock VectorStoreRepository vectorStoreRepository;
    @Mock QaPairRepository qaPairRepository;
    @Mock CrawlSiteRepository crawlSiteRepository;

    private static final Long KB_ID = 7L;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private KnowledgeServiceImpl service() {
        return new KnowledgeServiceImpl(knowledgeBaseRepository, documentRepository,
                vectorWriter, fileService, vectorStoreRepository,
                qaPairRepository, crawlSiteRepository);
    }

    /** 播种一个属于当前租户的空知识库，三类内容默认都是 0。 */
    private KnowledgeBase seedOwnedKb() {
        TenantContext.setTenantId(1L);
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setName("测试库");
        kb.setTenantId(1L);
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(kb));
        when(documentRepository.countByKnowledgeBaseId(KB_ID)).thenReturn(0L);
        when(qaPairRepository.countByKnowledgeBaseId(KB_ID)).thenReturn(0L);
        when(crawlSiteRepository.countByKnowledgeBaseId(KB_ID)).thenReturn(0L);
        return kb;
    }

    @Test
    @DisplayName("空知识库正常删除")
    void deletes_an_empty_knowledge_base() {
        KnowledgeBase kb = seedOwnedKb();

        service().deleteKnowledgeBase(KB_ID);

        verify(knowledgeBaseRepository).delete(kb);
    }

    @Test
    @DisplayName("还有文档时拒绝删除，文案报出篇数")
    void refuses_when_documents_remain() {
        seedOwnedKb();
        when(documentRepository.countByKnowledgeBaseId(KB_ID)).thenReturn(3L);

        assertThatThrownBy(() -> service().deleteKnowledgeBase(KB_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("3 篇文档");
        verify(knowledgeBaseRepository, never()).delete(any());
    }

    @Test
    @DisplayName("只剩问答对时同样拒绝——只查文档的实现会在这里放行然后 500")
    void refuses_when_only_qa_pairs_remain() {
        seedOwnedKb();
        when(qaPairRepository.countByKnowledgeBaseId(KB_ID)).thenReturn(5L);

        assertThatThrownBy(() -> service().deleteKnowledgeBase(KB_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("5 条问答对");
        verify(knowledgeBaseRepository, never()).delete(any());
    }

    @Test
    @DisplayName("只剩爬取站点时同样拒绝——文档删净但站点配置还在，正是站点爬取新增的场景")
    void refuses_when_only_crawl_sites_remain() {
        seedOwnedKb();
        when(crawlSiteRepository.countByKnowledgeBaseId(KB_ID)).thenReturn(2L);

        assertThatThrownBy(() -> service().deleteKnowledgeBase(KB_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2 个爬取站点");
        verify(knowledgeBaseRepository, never()).delete(any());
    }

    @Test
    @DisplayName("三类都有时文案把三者都列出来，且带上知识库名字")
    void message_lists_every_kind_of_remaining_content() {
        seedOwnedKb();
        when(documentRepository.countByKnowledgeBaseId(KB_ID)).thenReturn(3L);
        when(qaPairRepository.countByKnowledgeBaseId(KB_ID)).thenReturn(5L);
        when(crawlSiteRepository.countByKnowledgeBaseId(KB_ID)).thenReturn(2L);

        assertThatThrownBy(() -> service().deleteKnowledgeBase(KB_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("测试库")
                        .contains("3 篇文档")
                        .contains("5 条问答对")
                        .contains("2 个爬取站点"));
    }

    @Test
    @DisplayName("跨租户删除仍按归属校验拒绝，且一次 count 都不查——归属必须先于占用检查")
    void ownership_is_checked_before_occupancy() {
        TenantContext.setTenantId(1L);
        KnowledgeBase other = new KnowledgeBase();
        other.setId(KB_ID);
        other.setTenantId(999L);
        when(knowledgeBaseRepository.findById(KB_ID)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service().deleteKnowledgeBase(KB_ID))
                .isInstanceOf(RuntimeException.class);
        // 顺序写反（先数内容再校归属）会把「这个库里有多少东西」泄漏给非法调用方
        verify(documentRepository, never()).countByKnowledgeBaseId(any());
        verify(knowledgeBaseRepository, never()).delete(any());
    }
}
