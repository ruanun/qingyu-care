package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.QaPair;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.QaPairSource;
import com.qingyu.care.enums.QaPairStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.QaPairRepository;
import com.qingyu.care.util.SecurityUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReembedService 单测：范围解析归属校验 + 编排（遍历 KB → COMPLETED 文档 + ACTIVE QA 重嵌）
 * + 单条 fail-soft + 内存进度统计。
 * {@code @Async} 方法在单测中不经 Spring AOP 代理，self 指向自身即同步执行，可直接断言结果。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ReembedServiceTest {

    @Mock KnowledgeService knowledgeService;
    @Mock QaPairService qaPairService;
    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock DocumentRepository documentRepository;
    @Mock QaPairRepository qaPairRepository;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ReembedService service() {
        ReembedService service = new ReembedService(knowledgeService, qaPairService,
                knowledgeBaseRepository, documentRepository, qaPairRepository);
        service.setSelf(service); // 自注入：单测无 Spring 代理，指向自身即同步触发 runReembed
        return service;
    }

    private KnowledgeBase kb(long id) {
        return KnowledgeBase.builder().id(id).name("kb" + id).enabled(true).build();
    }

    private Document doc(long id, long kbId) {
        return Document.builder().id(id).name("doc" + id).knowledgeBase(kb(kbId))
                .status(DocumentStatus.COMPLETED).build();
    }

    private QaPair qa(long id, long kbId) {
        return QaPair.builder().id(id).knowledgeBase(kb(kbId)).question("问题" + id).answer("答案" + id)
                .source(QaPairSource.MANUAL).status(QaPairStatus.ACTIVE).build();
    }

    @Test
    void startReembed_scopeApp_遍历应用下全部知识库_单条失败不中断且进度统计正确() {
        TenantContext.setAppId(100L);
        when(knowledgeBaseRepository.findByAppId(100L)).thenReturn(List.of(kb(1L), kb(2L)));
        when(documentRepository.findByKnowledgeBaseIdAndStatus(1L, DocumentStatus.COMPLETED))
                .thenReturn(List.of(doc(11L, 1L), doc(12L, 1L)));
        when(documentRepository.findByKnowledgeBaseIdAndStatus(2L, DocumentStatus.COMPLETED))
                .thenReturn(List.of(doc(21L, 2L), doc(22L, 2L)));
        when(qaPairRepository.findByKnowledgeBaseIdAndStatus(1L, QaPairStatus.ACTIVE))
                .thenReturn(List.of(qa(31L, 1L)));
        when(qaPairRepository.findByKnowledgeBaseIdAndStatus(2L, QaPairStatus.ACTIVE))
                .thenReturn(List.of(qa(32L, 2L)));
        // 其中一个文档重嵌抛异常，不应中断整批；lenient 避免 Mockito 严格桩对其余未命中 id 的参数不匹配误报
        lenient().doThrow(new RuntimeException("下载失败")).when(knowledgeService).processDocument(12L);

        ReembedService svc = service();
        String jobId = svc.startReembed(ReembedService.Scope.APP, null);

        verify(knowledgeService, times(4)).processDocument(anyLong());
        verify(qaPairService, times(2)).reembed(any());

        ReembedService.Progress p = svc.getProgress(jobId);
        assertThat(p.total).isEqualTo(6);
        assertThat(p.done.get()).isEqualTo(5);
        assertThat(p.failed.get()).isEqualTo(1);
        assertThat(p.status).isEqualTo("COMPLETED");
        assertThat(p.scope).isEqualTo("APP");
        assertThat(p.finishedAt).isNotNull();
    }

    @Test
    void startReembed_scopeApp_未选应用_抛400且不查知识库() {
        ReembedService svc = service();

        assertThatThrownBy(() -> svc.startReembed(ReembedService.Scope.APP, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未选择应用");
        verify(knowledgeBaseRepository, never()).findByAppId(any());
    }

    @Test
    void startReembed_scopeAll_非平台管理员_抛403且不查知识库() {
        ReembedService svc = service();

        assertThatThrownBy(() -> svc.startReembed(ReembedService.Scope.ALL, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("平台管理员");
        verify(knowledgeBaseRepository, never()).findAll();
    }

    @Test
    void startReembed_scopeAll_平台管理员_遍历全平台知识库() {
        when(knowledgeBaseRepository.findAll()).thenReturn(List.of(kb(1L)));
        when(documentRepository.findByKnowledgeBaseIdAndStatus(1L, DocumentStatus.COMPLETED))
                .thenReturn(List.of());
        when(qaPairRepository.findByKnowledgeBaseIdAndStatus(1L, QaPairStatus.ACTIVE))
                .thenReturn(List.of());

        ReembedService svc = service();
        try (MockedStatic<SecurityUtil> sec = mockStatic(SecurityUtil.class)) {
            sec.when(SecurityUtil::isPlatformAdmin).thenReturn(true);
            String jobId = svc.startReembed(ReembedService.Scope.ALL, null);
            ReembedService.Progress p = svc.getProgress(jobId);
            assertThat(p.total).isEqualTo(0);
            assertThat(p.status).isEqualTo("COMPLETED");
        }
        verify(knowledgeBaseRepository).findAll();
    }

    @Test
    void startReembed_scopeKb_经getKnowledgeBase归属校验() {
        KnowledgeBase kb = kb(9L);
        when(knowledgeService.getKnowledgeBase(9L)).thenReturn(kb);
        when(documentRepository.findByKnowledgeBaseIdAndStatus(9L, DocumentStatus.COMPLETED))
                .thenReturn(List.of());
        when(qaPairRepository.findByKnowledgeBaseIdAndStatus(9L, QaPairStatus.ACTIVE))
                .thenReturn(List.of());

        ReembedService svc = service();
        svc.startReembed(ReembedService.Scope.KB, 9L);

        verify(knowledgeService).getKnowledgeBase(9L);
    }

    @Test
    void getProgress_未知jobId_抛404() {
        ReembedService svc = service();

        assertThatThrownBy(() -> svc.getProgress("不存在的任务id"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重建任务不存在");
    }
}
