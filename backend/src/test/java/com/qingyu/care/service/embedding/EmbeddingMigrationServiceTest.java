package com.qingyu.care.service.embedding;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.EmbeddingMigrationJob;
import com.qingyu.care.enums.EmbeddingJobKind;
import com.qingyu.care.enums.EmbeddingMigrationStatus;
import com.qingyu.care.enums.ModelType;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.EmbeddingMigrationJobRepository;
import com.qingyu.care.repository.QaPairRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import com.qingyu.care.service.KnowledgeService;
import com.qingyu.care.service.LongTaskTenantLimiter;
import com.qingyu.care.service.QaPairService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 切换向量模型：同标签只换指针、不同标签走影子重建；补漏扫描有缺口不翻转；取消回滚。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingMigrationServiceTest {

    @Mock EmbeddingModelResolver resolver;
    @Mock ApplicationRepository applicationRepository;
    @Mock EmbeddingMigrationJobRepository jobRepository;
    @Mock DocumentRepository documentRepository;
    @Mock QaPairRepository qaPairRepository;
    @Mock VectorStoreRepository vectorStoreRepository;
    @Mock KnowledgeService knowledgeService;
    @Mock QaPairService qaPairService;
    @Mock PlatformTransactionManager transactionManager;
    @Mock LongTaskTenantLimiter limiter;
    @Mock LongTaskTenantLimiter.Permit permit;

    EmbeddingMigrationService service;

    private final AiModelConfig a = AiModelConfig.builder().id(1L).modelId("qwen3.7-text-embedding")
            .modelType(ModelType.EMBEDDING).build();
    private final AiModelConfig b = AiModelConfig.builder().id(2L).modelId("bge-m3")
            .modelType(ModelType.EMBEDDING).build();

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new EmbeddingMigrationService(resolver, applicationRepository, jobRepository, documentRepository,
                qaPairRepository, vectorStoreRepository, knowledgeService, qaPairService, transactionManager);
        service.setSelf(service);
        service.setLongTaskLimiter(limiter);
        TenantContext.setTenantId(10L);
        TenantContext.setAppId(5L);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private EmbeddingMigrationJob runningJob() {
        return EmbeddingMigrationJob.builder().id(30L).tenantId(10L).appId(5L).fromModelId(1L)
                .fromTag("qwen3.7-text-embedding").toModelId(2L).toTag("bge-m3")
                .status(EmbeddingMigrationStatus.RUNNING).build();
    }

    // ===== 发起切换 =====

    @Test
    void 同标签_只换指针_不建任务不占名额() {
        AiModelConfig sameSpace = AiModelConfig.builder().id(3L).modelId("qwen3.7-text-embedding")
                .modelType(ModelType.EMBEDDING).build();
        when(resolver.requireSelectable(5L, 3L)).thenReturn(sameSpace);
        when(resolver.active(5L)).thenReturn(a);
        when(applicationRepository.setActiveEmbeddingModel(5L, 3L)).thenReturn(1);

        var result = service.switchModel(3L);

        assertThat(result.outcome()).isEqualTo(EmbeddingMigrationService.SwitchOutcome.SWITCHED);
        verifyNoInteractions(limiter);
        verify(jobRepository, never()).save(any());
        verify(applicationRepository, never()).startPendingEmbeddingModel(any(), any());
    }

    @Test
    void 不同标签_置pending建任务并投递() {
        when(resolver.requireSelectable(5L, 2L)).thenReturn(b);
        when(resolver.active(5L)).thenReturn(a);
        when(limiter.acquire()).thenReturn(permit);
        when(applicationRepository.startPendingEmbeddingModel(5L, 2L)).thenReturn(1);
        when(jobRepository.save(any())).thenAnswer(inv -> {
            EmbeddingMigrationJob j = inv.getArgument(0);
            j.setId(30L);
            return j;
        });

        var result = service.switchModel(2L);

        assertThat(result.outcome()).isEqualTo(EmbeddingMigrationService.SwitchOutcome.MIGRATING);
        assertThat(result.jobId()).isEqualTo(30L);
        ArgumentCaptor<EmbeddingMigrationJob> cap = ArgumentCaptor.forClass(EmbeddingMigrationJob.class);
        verify(jobRepository).save(cap.capture());
        assertThat(cap.getValue().getFromTag()).isEqualTo("qwen3.7-text-embedding");
        assertThat(cap.getValue().getToTag()).isEqualTo("bge-m3");
        assertThat(cap.getValue().getAppId()).isEqualTo(5L);
        verify(permit).launch(any());   // 没有活动事务时 AfterCommit 立即执行
    }

    @Test
    void 已有切换进行中_拒绝() {
        when(resolver.requireSelectable(5L, 2L)).thenReturn(b);
        when(resolver.active(5L)).thenReturn(a);
        when(resolver.pending(5L)).thenReturn(AiModelConfig.builder().id(4L).modelId("x").build());

        assertThatThrownBy(() -> service.switchModel(2L)).isInstanceOf(InvalidOperationException.class);
        verifyNoInteractions(jobRepository);
    }

    @Test
    void 选的就是当前模型_什么都不做() {
        when(resolver.requireSelectable(5L, 1L)).thenReturn(a);
        when(resolver.active(5L)).thenReturn(a);

        assertThat(service.switchModel(1L).outcome()).isEqualTo(EmbeddingMigrationService.SwitchOutcome.UNCHANGED);
        verifyNoInteractions(limiter);
        verify(jobRepository, never()).save(any());
    }

    // ===== 执行 =====

    private void stubRunnableJob() {
        when(jobRepository.findById(30L)).thenReturn(Optional.of(runningJob()));
        when(resolver.pending(5L)).thenReturn(b);
        when(documentRepository.findCompletedIdsByAppId(5L)).thenReturn(List.of(100L, 101L));
        when(qaPairRepository.findEmbeddedIdsByAppId(5L)).thenReturn(List.of(200L));
    }

    @Test
    void 全部补齐_翻转指针并清旧向量_最后完成() throws Exception {
        stubRunnableJob();
        when(vectorStoreRepository.findDocumentIdsMissingTag(5L, "bge-m3")).thenReturn(List.of());
        when(vectorStoreRepository.findQaPairIdsMissingTag(5L, "bge-m3")).thenReturn(List.of());
        when(applicationRepository.promotePendingEmbeddingModel(5L, 2L)).thenReturn(1);

        service.run(30L);

        verify(knowledgeService).reembedDocumentFor(100L, b);
        verify(knowledgeService).reembedDocumentFor(101L, b);
        verify(qaPairService).reembedFor(200L, b);
        InOrder ord = inOrder(applicationRepository, vectorStoreRepository);
        ord.verify(applicationRepository).promotePendingEmbeddingModel(5L, 2L);
        // 清的是「不等于新标签」的全部向量，不是只清旧标签：历次中断残留也一并清掉
        ord.verify(vectorStoreRepository).deleteByAppIdExceptTag("5", "bge-m3");
        verify(jobRepository).transition(eq(30L), anySet(), eq(EmbeddingMigrationStatus.COMPLETED), any(), any());
    }

    @Test
    void 补漏后仍有缺口_不翻转_停在FAILED() throws Exception {
        stubRunnableJob();
        when(vectorStoreRepository.findDocumentIdsMissingTag(5L, "bge-m3")).thenReturn(List.of(101L));
        when(vectorStoreRepository.findQaPairIdsMissingTag(5L, "bge-m3")).thenReturn(List.of());

        service.run(30L);

        verify(applicationRepository, never()).promotePendingEmbeddingModel(any(), any());
        verify(vectorStoreRepository, never()).deleteByAppIdExceptTag(any(), any());
        verify(jobRepository).transition(eq(30L), anySet(), eq(EmbeddingMigrationStatus.FAILED), contains("1 项"), any());
    }

    @Test
    void 单篇失败不中断整批() throws Exception {
        stubRunnableJob();
        when(knowledgeService.reembedDocumentFor(100L, b)).thenThrow(new RuntimeException("上游 500"));
        when(vectorStoreRepository.findDocumentIdsMissingTag(5L, "bge-m3")).thenReturn(List.of());
        when(vectorStoreRepository.findQaPairIdsMissingTag(5L, "bge-m3")).thenReturn(List.of());
        when(applicationRepository.promotePendingEmbeddingModel(5L, 2L)).thenReturn(1);

        service.run(30L);

        verify(knowledgeService).reembedDocumentFor(101L, b);
        verify(qaPairService).reembedFor(200L, b);
        verify(jobRepository, atLeastOnce()).updateProgress(30L, 3, 2, 1);
    }

    @Test
    void 运行中收到取消_回滚目标向量与pending_不翻转() throws Exception {
        EmbeddingMigrationJob cancelling = runningJob();
        cancelling.setStatus(EmbeddingMigrationStatus.CANCELLING);
        when(jobRepository.findById(30L)).thenReturn(Optional.of(runningJob()), Optional.of(cancelling));
        when(resolver.pending(5L)).thenReturn(b);
        when(documentRepository.findCompletedIdsByAppId(5L)).thenReturn(List.of(100L));
        when(qaPairRepository.findEmbeddedIdsByAppId(5L)).thenReturn(List.of());

        service.run(30L);

        verify(knowledgeService, never()).reembedDocumentFor(any(), any());
        verify(applicationRepository).clearPendingEmbeddingModel(5L, 2L);
        verify(vectorStoreRepository).deleteByAppIdAndTag("5", "bge-m3");
        verify(applicationRepository, never()).promotePendingEmbeddingModel(any(), any());
    }

    @Test
    void pending已被清掉_任务直接结束_不重嵌() throws Exception {
        when(jobRepository.findById(30L)).thenReturn(Optional.of(runningJob()));
        when(resolver.pending(5L)).thenReturn(null);

        service.run(30L);

        verifyNoInteractions(knowledgeService, qaPairService);
        verify(jobRepository).transition(eq(30L), anySet(), eq(EmbeddingMigrationStatus.CANCELLED), any(), any());
    }

    // ===== 用当前模型原地重建 =====

    private EmbeddingMigrationJob rebuildJob() {
        return EmbeddingMigrationJob.builder().id(40L).tenantId(10L).appId(5L).kind(EmbeddingJobKind.REBUILD)
                .fromModelId(1L).fromTag("qwen3.7-text-embedding").toModelId(1L).toTag("qwen3.7-text-embedding")
                .status(EmbeddingMigrationStatus.RUNNING).build();
    }

    @Test
    void 发起重建_任务指向当前模型_不碰指针() {
        when(resolver.active(5L)).thenReturn(a);
        when(limiter.acquire()).thenReturn(permit);
        when(jobRepository.save(any())).thenAnswer(inv -> {
            EmbeddingMigrationJob j = inv.getArgument(0);
            j.setId(40L);
            return j;
        });

        assertThat(service.rebuild()).isEqualTo(40L);

        ArgumentCaptor<EmbeddingMigrationJob> cap = ArgumentCaptor.forClass(EmbeddingMigrationJob.class);
        verify(jobRepository).save(cap.capture());
        assertThat(cap.getValue().getKind()).isEqualTo(EmbeddingJobKind.REBUILD);
        assertThat(cap.getValue().getToModelId()).isEqualTo(1L);
        verify(applicationRepository, never()).startPendingEmbeddingModel(any(), any());
        verify(permit).launch(any());
    }

    @Test
    void 切换挂起或有任务在跑时_拒绝重建() {
        when(resolver.active(5L)).thenReturn(a);
        when(jobRepository.findFirstByAppIdOrderByIdDesc(5L)).thenReturn(Optional.of(runningJob()));

        assertThatThrownBy(() -> service.rebuild()).isInstanceOf(InvalidOperationException.class);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void 重建运行中_拒绝切换_避免翻转后又写回旧标签() {
        when(resolver.requireSelectable(5L, 2L)).thenReturn(b);
        when(resolver.active(5L)).thenReturn(a);
        when(jobRepository.findFirstByAppIdOrderByIdDesc(5L)).thenReturn(Optional.of(rebuildJob()));

        assertThatThrownBy(() -> service.switchModel(2L)).isInstanceOf(InvalidOperationException.class);
        verify(applicationRepository, never()).startPendingEmbeddingModel(any(), any());
    }

    @Test
    void 执行重建_用当前模型逐条重嵌_不翻转不清理() throws Exception {
        when(jobRepository.findById(40L)).thenReturn(Optional.of(rebuildJob()));
        when(resolver.active(5L)).thenReturn(a);
        when(documentRepository.findCompletedIdsByAppId(5L)).thenReturn(List.of(100L));
        when(qaPairRepository.findEmbeddedIdsByAppId(5L)).thenReturn(List.of(200L));

        service.run(40L);

        verify(knowledgeService).reembedDocumentFor(100L, a);
        verify(qaPairService).reembedFor(200L, a);
        verify(applicationRepository, never()).promotePendingEmbeddingModel(any(), any());
        verify(vectorStoreRepository, never()).deleteByAppIdExceptTag(any(), any());
        verify(vectorStoreRepository, never()).deleteByAppIdAndTag(any(), any());
        verify(jobRepository).transition(eq(40L), anySet(), eq(EmbeddingMigrationStatus.COMPLETED), any(), any());
    }

    @Test
    void 重建有失败项_停在FAILED可重试() throws Exception {
        when(jobRepository.findById(40L)).thenReturn(Optional.of(rebuildJob()));
        when(resolver.active(5L)).thenReturn(a);
        when(documentRepository.findCompletedIdsByAppId(5L)).thenReturn(List.of(100L));
        when(qaPairRepository.findEmbeddedIdsByAppId(5L)).thenReturn(List.of());
        when(knowledgeService.reembedDocumentFor(100L, a)).thenThrow(new RuntimeException("上游 500"));

        service.run(40L);

        verify(jobRepository).transition(eq(40L), anySet(), eq(EmbeddingMigrationStatus.FAILED), contains("1 项"), any());
    }

    @Test
    void 取消已失败的重建_只改状态_不碰指针与向量() {
        EmbeddingMigrationJob failed = rebuildJob();
        failed.setStatus(EmbeddingMigrationStatus.FAILED);
        when(jobRepository.findById(40L)).thenReturn(Optional.of(failed));

        service.cancel(40L);

        verify(jobRepository).transition(eq(40L), anySet(), eq(EmbeddingMigrationStatus.CANCELLED), any(), any());
        verifyNoInteractions(vectorStoreRepository);
        verify(applicationRepository, never()).clearPendingEmbeddingModel(any(), any());
    }

    // ===== 取消 / 归属 =====

    @Test
    void 取消已失败的任务_当场回滚() {
        EmbeddingMigrationJob failed = runningJob();
        failed.setStatus(EmbeddingMigrationStatus.FAILED);
        when(jobRepository.findById(30L)).thenReturn(Optional.of(failed));

        service.cancel(30L);

        verify(applicationRepository).clearPendingEmbeddingModel(5L, 2L);
        verify(vectorStoreRepository).deleteByAppIdAndTag("5", "bge-m3");
    }

    @Test
    void 别的应用的任务_按不存在处理() {
        EmbeddingMigrationJob other = runningJob();
        other.setAppId(6L);
        when(jobRepository.findById(30L)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.cancel(30L)).hasMessageContaining("不存在");
        verify(jobRepository, never()).transition(any(), anySet(), any(), any(), any());
    }
}
