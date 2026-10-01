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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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
        service.setLongTaskLimiter(new LongTaskTenantLimiter()); // 未注入上限即关闭，只管走通
        return service;
    }

    private KnowledgeBase kb(long id) {
        return KnowledgeBase.builder().id(id).name("kb" + id).enabled(true).build();
    }

    private Document doc(long id, long kbId) {
        return Document.builder().id(id).name("doc" + id).knowledgeBase(kb(kbId))
                .status(DocumentStatus.COMPLETED).build();
    }

    /** 模拟 processDocument 内部吞异常后落库的终态：状态仍为 FAILED（不重抛）。 */
    private Document failedDoc(long id, long kbId) {
        return Document.builder().id(id).name("doc" + id).knowledgeBase(kb(kbId))
                .status(DocumentStatus.FAILED).build();
    }

    private QaPair qa(long id, long kbId) {
        return QaPair.builder().id(id).knowledgeBase(kb(kbId)).question("问题" + id).answer("答案" + id)
                .source(QaPairSource.MANUAL).status(QaPairStatus.ACTIVE).build();
    }

    @Test
    void startReembed_scopeApp_遍历应用下全部知识库_单条失败不中断且进度统计正确() {
        TenantContext.setTenantId(7L); // 进度按发起租户隔离，查询方须同租户
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
        // processDocument 内部吞异常置 FAILED、不重抛（真实语义），故用回读状态模拟"其中一个文档处理失败"：
        // 12L 处理后状态仍为 FAILED，其余处理后为 COMPLETED；knowledgeService.processDocument 保持 void 不抛
        when(documentRepository.findById(11L)).thenReturn(Optional.of(doc(11L, 1L)));
        when(documentRepository.findById(12L)).thenReturn(Optional.of(failedDoc(12L, 1L)));
        when(documentRepository.findById(21L)).thenReturn(Optional.of(doc(21L, 2L)));
        when(documentRepository.findById(22L)).thenReturn(Optional.of(doc(22L, 2L)));

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
    void startReembed_QA重嵌抛异常_faisoft不中断且计入failed() {
        // 覆盖文档路以外的另一条 fail-soft 路径：QaPairService.reembed 本身会抛异常（非吞异常置状态），
        // ReembedService 的 catch 直接捕获计入 failed，与文档路"回读状态判成败"互为对称验证
        TenantContext.setTenantId(7L); // 进度按发起租户隔离，查询方须同租户
        KnowledgeBase kb = kb(9L);
        when(knowledgeService.getKnowledgeBase(9L)).thenReturn(kb);
        when(documentRepository.findByKnowledgeBaseIdAndStatus(9L, DocumentStatus.COMPLETED))
                .thenReturn(List.of());
        QaPair qa91 = qa(91L, 9L);
        QaPair qa92 = qa(92L, 9L);
        when(qaPairRepository.findByKnowledgeBaseIdAndStatus(9L, QaPairStatus.ACTIVE))
                .thenReturn(List.of(qa91, qa92));
        // 显式桩 qa91 的成功路径：避免 Mockito 严格模式下与 qa92 的失败桩产生"参数不匹配"误判
        doNothing().when(qaPairService).reembed(qa91);
        doThrow(new RuntimeException("嵌入服务异常")).when(qaPairService).reembed(qa92);

        ReembedService svc = service();
        String jobId = svc.startReembed(ReembedService.Scope.KB, 9L);

        ReembedService.Progress p = svc.getProgress(jobId);
        assertThat(p.total).isEqualTo(2);
        assertThat(p.done.get()).isEqualTo(1);
        assertThat(p.failed.get()).isEqualTo(1);
        assertThat(p.status).isEqualTo("COMPLETED");
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

    /** 2026-09-30 安全审查：别的租户拿到 jobId 也查不到进度，且与「不存在」同一响应 */
    @Test
    void getProgress_别的租户查不到() {
        TenantContext.setTenantId(7L);
        TenantContext.setAppId(100L);
        when(knowledgeBaseRepository.findByAppId(100L)).thenReturn(List.of());
        ReembedService svc = service();
        String jobId = svc.startReembed(ReembedService.Scope.APP, null);

        TenantContext.setTenantId(8L);
        assertThatThrownBy(() -> svc.getProgress(jobId))
                .isInstanceOf(com.qingyu.care.exception.BusinessException.class)
                .hasMessageContaining("不存在");
    }
}
