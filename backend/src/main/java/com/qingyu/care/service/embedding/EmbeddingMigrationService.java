package com.qingyu.care.service.embedding;

import com.qingyu.care.config.AsyncConfig;
import com.qingyu.care.config.TenantContext;
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.entity.EmbeddingMigrationJob;
import com.qingyu.care.enums.EmbeddingJobKind;
import com.qingyu.care.enums.EmbeddingMigrationStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.InvalidOperationException;
import com.qingyu.care.repository.ApplicationRepository;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.EmbeddingMigrationJobRepository;
import com.qingyu.care.repository.QaPairRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import com.qingyu.care.service.KnowledgeService;
import com.qingyu.care.service.LongTaskTenantLimiter;
import com.qingyu.care.service.QaPairService;
import com.qingyu.care.util.AfterCommit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 切换应用的向量模型：影子重建，检索全程不中断。
 *
 * <p>流程：
 * <ol>
 *   <li>目标与当前同标签（同一个模型换了托管 / Key）→ 向量空间没变，直接换指针，不重嵌；</li>
 *   <li>否则置 pending（此后新写入一律双写，见 {@link EmbeddingModelResolver#writeTargets}），
 *       异步逐篇给目标模型补向量——检索这期间照常读当前模型；</li>
 *   <li>补漏扫描：把「目标标签下一条向量都没有」的文档 / QA 再补一遍（迁移开始后才完成首次处理、
 *       又没赶上双写的那些）；仍有缺口就停在 FAILED，不翻转——带着缺口切过去，那些内容就从检索里消失了；</li>
 *   <li>CAS 翻转 pending → active，再清掉本应用所有非当前标签的向量。</li>
 * </ol>
 * 同类产品（Dify / MaxKB / Open WebUI）都是先清空再重建，期间该库检索为空；FastGPT 立即切换、新旧向量混存。
 *
 * @author ruan
 */
@Service
@Slf4j
public class EmbeddingMigrationService {

    /** 补漏扫描最多跑几轮：每轮都可能因为文档锁被占而跳过一些，多给几次机会 */
    private static final int MAX_SWEEP_ROUNDS = 3;

    private static final Set<EmbeddingMigrationStatus> ACTIVE = Set.of(
            EmbeddingMigrationStatus.RUNNING, EmbeddingMigrationStatus.CANCELLING);
    private static final Set<EmbeddingMigrationStatus> RESUMABLE = Set.of(
            EmbeddingMigrationStatus.FAILED, EmbeddingMigrationStatus.INTERRUPTED);

    private final EmbeddingModelResolver resolver;
    private final ApplicationRepository applicationRepository;
    private final EmbeddingMigrationJobRepository jobRepository;
    private final DocumentRepository documentRepository;
    private final QaPairRepository qaPairRepository;
    private final VectorStoreRepository vectorStoreRepository;
    private final KnowledgeService knowledgeService;
    private final QaPairService qaPairService;
    private final TransactionTemplate tx;

    private EmbeddingMigrationService self;
    private LongTaskTenantLimiter longTaskLimiter;

    public EmbeddingMigrationService(EmbeddingModelResolver resolver,
                                     ApplicationRepository applicationRepository,
                                     EmbeddingMigrationJobRepository jobRepository,
                                     DocumentRepository documentRepository,
                                     QaPairRepository qaPairRepository,
                                     VectorStoreRepository vectorStoreRepository,
                                     KnowledgeService knowledgeService,
                                     QaPairService qaPairService,
                                     PlatformTransactionManager transactionManager) {
        this.resolver = resolver;
        this.applicationRepository = applicationRepository;
        this.jobRepository = jobRepository;
        this.documentRepository = documentRepository;
        this.qaPairRepository = qaPairRepository;
        this.vectorStoreRepository = vectorStoreRepository;
        this.knowledgeService = knowledgeService;
        this.qaPairService = qaPairService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 自注入：@Async 同类调用不经代理就不生效（同 KnowledgeServiceImpl 范式） */
    @Autowired
    @Lazy
    public void setSelf(EmbeddingMigrationService self) {
        this.self = self;
    }

    /** setter 注入，理由同 KnowledgeServiceImpl.setLongTaskLimiter */
    @Autowired
    public void setLongTaskLimiter(LongTaskTenantLimiter longTaskLimiter) {
        this.longTaskLimiter = longTaskLimiter;
    }

    /** 切换结果：SWITCHED = 同一向量空间、已直接生效；MIGRATING = 已开始影子重建；UNCHANGED = 本就是当前模型 */
    public enum SwitchOutcome { SWITCHED, MIGRATING, UNCHANGED }

    public record SwitchResult(SwitchOutcome outcome, Long jobId) {
    }

    /**
     * 把当前应用的向量模型切到 {@code targetModelId}。请求线程调用（依赖 TenantContext），越权 / 冲突直接抛。
     */
    @Transactional
    public SwitchResult switchModel(Long targetModelId) {
        Long appId = requireAppId();
        AiModelConfig target = resolver.requireSelectable(appId, targetModelId);
        AiModelConfig active = resolver.active(appId);
        if (resolver.pending(appId) != null) {
            throw new InvalidOperationException("已有一次向量模型切换在进行中，请等它完成或先取消");
        }
        requireNoRunningJob(appId);
        if (active.getId().equals(target.getId())) {
            return new SwitchResult(SwitchOutcome.UNCHANGED, null);
        }
        if (EmbeddingTag.of(active).equals(EmbeddingTag.of(target))) {
            // 同一个模型（标签相同）换了托管或 Key：已有向量完全可用，换指针即可
            if (applicationRepository.setActiveEmbeddingModel(appId, target.getId()) == 0) {
                throw new InvalidOperationException("已有一次向量模型切换在进行中，请等它完成或先取消");
            }
            log.info("应用 {} 向量模型直接切换 {} → {}（同标签 {}，无需重嵌）", appId, active.getId(), target.getId(),
                    EmbeddingTag.of(target));
            return new SwitchResult(SwitchOutcome.SWITCHED, null);
        }

        // 先占名额：超限时什么都还没改（安全审查 M6）
        LongTaskTenantLimiter.Permit permit = longTaskLimiter.acquire();
        if (applicationRepository.startPendingEmbeddingModel(appId, target.getId()) == 0) {
            throw new InvalidOperationException("已有一次向量模型切换在进行中，请等它完成或先取消");
        }
        EmbeddingMigrationJob job = jobRepository.save(EmbeddingMigrationJob.builder()
                .tenantId(TenantContext.getTenantId())
                .appId(appId)
                .kind(EmbeddingJobKind.SWITCH)
                .fromModelId(active.getId())
                .fromTag(EmbeddingTag.of(active))
                .toModelId(target.getId())
                .toTag(EmbeddingTag.of(target))
                .status(EmbeddingMigrationStatus.RUNNING)
                .total(0).done(0).failed(0)
                .build());
        Long jobId = job.getId();
        // 提交之后再投递：异步线程要读到 pending 与任务行（同 WebPageSyncService 的约定）
        AfterCommit.run(() -> permit.launch(() -> self.run(jobId)));
        log.info("应用 {} 开始切换向量模型 {} → {}，任务 {}", appId, EmbeddingTag.of(active), EmbeddingTag.of(target), jobId);
        return new SwitchResult(SwitchOutcome.MIGRATING, jobId);
    }

    /**
     * 用当前向量模型原地重建本应用全部向量（模型没换、向量化的输入或切分方式变了时用，如 2026-10-06 起只嵌入正文）。
     *
     * <p>标签不变，不走影子重建：逐篇调 {@code reembedDocumentFor(doc, 当前模型)}，它在<b>一个事务</b>里先删本篇该标签的向量
     * 再写新向量——提交前其他连接看到的仍是旧向量，所以重建期间检索不中断（MVCC 保证，不是靠双写）。
     * 取消只是停下：已重建的部分与没重建的部分同一个模型、同一个向量空间，混着检索没有问题。
     */
    @Transactional
    public Long rebuild() {
        Long appId = requireAppId();
        AiModelConfig active = resolver.active(appId);
        if (resolver.pending(appId) != null) {
            throw new InvalidOperationException("有一次向量模型切换还没结束，请等它完成或先取消");
        }
        requireNoRunningJob(appId);
        LongTaskTenantLimiter.Permit permit = longTaskLimiter.acquire();
        EmbeddingMigrationJob job = jobRepository.save(EmbeddingMigrationJob.builder()
                .tenantId(TenantContext.getTenantId())
                .appId(appId)
                .kind(EmbeddingJobKind.REBUILD)
                .fromModelId(active.getId())
                .fromTag(EmbeddingTag.of(active))
                .toModelId(active.getId())
                .toTag(EmbeddingTag.of(active))
                .status(EmbeddingMigrationStatus.RUNNING)
                .total(0).done(0).failed(0)
                .build());
        Long jobId = job.getId();
        AfterCommit.run(() -> permit.launch(() -> self.run(jobId)));
        log.info("应用 {} 开始用当前向量模型 {} 重建向量，任务 {}", appId, EmbeddingTag.of(active), jobId);
        return jobId;
    }

    /** 取消：运行中的任务置 CANCELLING，由任务在检查点自行清理；已停下的（失败 / 中断）当场清理。 */
    @Transactional
    public void cancel(Long jobId) {
        EmbeddingMigrationJob job = requireOwnedJob(jobId);
        if (job.getStatus() == EmbeddingMigrationStatus.RUNNING) {
            jobRepository.transition(jobId, Set.of(EmbeddingMigrationStatus.RUNNING),
                    EmbeddingMigrationStatus.CANCELLING, "正在取消…", null);
            return;
        }
        if (RESUMABLE.contains(job.getStatus())) {
            if (EmbeddingJobKind.orDefault(job.getKind()) == EmbeddingJobKind.REBUILD) {
                // 重建没有挂起的指针、没有要回滚的向量，标成已取消即可
                jobRepository.transition(jobId, RESUMABLE, EmbeddingMigrationStatus.CANCELLED,
                        "已取消，已重建的部分保留", LocalDateTime.now());
            } else {
                rollBack(job);
            }
            return;
        }
        throw new InvalidOperationException("该任务已结束，无需取消");
    }

    /** 继续一个失败 / 被重启打断的任务（pending 仍在，已补的向量不会重复：逐条先删目标标签再写）。 */
    @Transactional
    public void resume(Long jobId) {
        EmbeddingMigrationJob job = requireOwnedJob(jobId);
        if (!RESUMABLE.contains(job.getStatus())) {
            throw new InvalidOperationException("只有失败或被中断的任务可以继续");
        }
        if (EmbeddingJobKind.orDefault(job.getKind()) == EmbeddingJobKind.REBUILD) {
            if (!resolver.active(job.getAppId()).getId().equals(job.getToModelId())) {
                throw new InvalidOperationException("向量模型已变更，请重新发起重建");
            }
        } else {
            AiModelConfig pending = resolver.pending(job.getAppId());
            if (pending == null || !pending.getId().equals(job.getToModelId())) {
                throw new InvalidOperationException("该切换已不再挂起，请重新发起");
            }
        }
        LongTaskTenantLimiter.Permit permit = longTaskLimiter.acquire();
        if (jobRepository.transition(jobId, RESUMABLE, EmbeddingMigrationStatus.RUNNING, null, null) == 0) {
            throw new InvalidOperationException("任务状态已变化，请刷新后重试");
        }
        AfterCommit.run(() -> permit.launch(() -> self.run(jobId)));
    }

    /** 当前应用最近一次任务；没有则为 null。 */
    public EmbeddingMigrationJob latestJob(Long appId) {
        return jobRepository.findFirstByAppIdOrderByIdDesc(appId).orElse(null);
    }

    /**
     * 执行迁移。⚠️ 跑在没有 TenantContext 的长任务线程里：appId 来自任务行，模型由 {@link EmbeddingModelResolver}
     * 按 id 读取，文档 / QA 按 app_id 原生查询，不依赖任何过滤器。
     */
    @Async(AsyncConfig.LONG_TASK_EXECUTOR)
    public CompletableFuture<Void> run(Long jobId) {
        try {
            execute(jobId);
        } catch (Exception e) {
            log.error("向量模型迁移任务异常 jobId={}", jobId, e);
            transition(jobId, ACTIVE, EmbeddingMigrationStatus.FAILED,
                    "任务异常中止（" + e.getClass().getSimpleName() + "），可重试或取消", true);
        }
        // 返回 future：LongTaskTenantLimiter 在任务真正结束时归还名额
        return CompletableFuture.completedFuture(null);
    }

    private void execute(Long jobId) {
        EmbeddingMigrationJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || !job.getStatus().isActive()) {
            return;
        }
        Long appId = job.getAppId();
        if (EmbeddingJobKind.orDefault(job.getKind()) == EmbeddingJobKind.REBUILD) {
            executeRebuild(job);
            return;
        }
        AiModelConfig target = resolver.pending(appId);
        if (target == null || !target.getId().equals(job.getToModelId())) {
            transition(jobId, ACTIVE, EmbeddingMigrationStatus.CANCELLED, "切换已不再挂起", true);
            return;
        }

        List<Long> docIds = documentRepository.findCompletedIdsByAppId(appId);
        List<Long> qaIds = qaPairRepository.findEmbeddedIdsByAppId(appId);
        int total = docIds.size() + qaIds.size();
        int[] done = {0};
        int[] failed = {0};
        progress(jobId, total, 0, 0);

        for (Long docId : docIds) {
            if (cancelRequested(jobId)) {
                rollBack(jobRepository.findById(jobId).orElseThrow());
                return;
            }
            // 被文档锁挡下（别的处理正在进行）不算失败：那一轮会按写入目标双写，补漏扫描也会再看一眼
            try {
                knowledgeService.reembedDocumentFor(docId, target);
                done[0]++;
            } catch (Exception e) {
                failed[0]++;
                log.warn("迁移重嵌文档失败 jobId={} docId={}: {}", jobId, docId, e.getMessage());
            }
            progress(jobId, total, done[0], failed[0]);
        }
        for (Long qaId : qaIds) {
            if (cancelRequested(jobId)) {
                rollBack(jobRepository.findById(jobId).orElseThrow());
                return;
            }
            try {
                qaPairService.reembedFor(qaId, target);
                done[0]++;
            } catch (Exception e) {
                failed[0]++;
                log.warn("迁移重嵌 QA 失败 jobId={} qaId={}: {}", jobId, qaId, e.getMessage());
            }
            progress(jobId, total, done[0], failed[0]);
        }

        int missing = sweep(jobId, appId, target);
        if (missing < 0) {
            return;   // 扫描途中被取消，已清理
        }
        if (missing > 0) {
            transition(jobId, ACTIVE, EmbeddingMigrationStatus.FAILED,
                    missing + " 项内容未能写入新模型（查看文档的处理状态，修复后可重试），应用仍在使用原模型", true);
            return;
        }

        // 翻转与收尾清理放在一个事务里：翻转成功才清旧向量；CAS 失败说明刚被取消
        boolean promoted = Boolean.TRUE.equals(tx.execute(s -> {
            if (applicationRepository.promotePendingEmbeddingModel(appId, target.getId()) == 0) {
                return false;
            }
            int removed = vectorStoreRepository.deleteByAppIdExceptTag(appId.toString(), EmbeddingTag.of(target));
            log.info("应用 {} 已切换到向量模型 {}，清理旧向量 {} 条", appId, EmbeddingTag.of(target), removed);
            return true;
        }));
        if (!promoted) {
            transition(jobId, ACTIVE, EmbeddingMigrationStatus.CANCELLED, "切换已被取消", true);
            return;
        }
        // 翻转后再扫一次：翻转前一瞬间才完成处理、只写了旧模型的内容，此时在新模型下缺失
        int late = sweepOnce(appId, target);
        transition(jobId, ACTIVE, EmbeddingMigrationStatus.COMPLETED,
                late > 0 ? "已完成（另有 " + late + " 项在收尾时补写）" : null, true);
    }

    /** 原地重建：逐条在一个事务里删本条该标签的向量再写新向量；取消就停下，不回滚。 */
    private void executeRebuild(EmbeddingMigrationJob job) {
        Long jobId = job.getId();
        Long appId = job.getAppId();
        AiModelConfig model = resolver.active(appId);
        if (!model.getId().equals(job.getToModelId())) {
            transition(jobId, ACTIVE, EmbeddingMigrationStatus.CANCELLED, "向量模型已变更，重建已停止", true);
            return;
        }
        List<Long> docIds = documentRepository.findCompletedIdsByAppId(appId);
        List<Long> qaIds = qaPairRepository.findEmbeddedIdsByAppId(appId);
        int total = docIds.size() + qaIds.size();
        int done = 0;
        int failed = 0;
        progress(jobId, total, 0, 0);
        for (int i = 0; i < total; i++) {
            if (cancelRequested(jobId)) {
                transition(jobId, ACTIVE, EmbeddingMigrationStatus.CANCELLED, "已取消，已重建的部分保留", true);
                return;
            }
            try {
                if (i < docIds.size()) {
                    // 被文档锁挡下说明另一轮处理正在进行，它写出的同样是新口径的向量，不算失败
                    knowledgeService.reembedDocumentFor(docIds.get(i), model);
                } else {
                    qaPairService.reembedFor(qaIds.get(i - docIds.size()), model);
                }
                done++;
            } catch (Exception e) {
                failed++;
                log.warn("重建向量失败 jobId={} 第 {} 项: {}", jobId, i, e.getMessage());
            }
            progress(jobId, total, done, failed);
        }
        if (failed > 0) {
            transition(jobId, ACTIVE, EmbeddingMigrationStatus.FAILED,
                    failed + " 项重建失败（它们仍保留旧向量、照常可检索），可重试", true);
        } else {
            transition(jobId, ACTIVE, EmbeddingMigrationStatus.COMPLETED, null, true);
        }
    }

    /** 同一应用同一时间只跑一个向量任务：切换与重建交错，会在翻转后又写回旧标签的向量。 */
    private void requireNoRunningJob(Long appId) {
        EmbeddingMigrationJob latest = latestJob(appId);
        if (latest != null && latest.getStatus().isActive()) {
            throw new InvalidOperationException("本应用有向量任务正在进行，请等它完成或先取消");
        }
    }

    /**
     * 补漏扫描。返回仍缺失的条目数；途中被取消返回 -1（已清理）。
     */
    private int sweep(Long jobId, Long appId, AiModelConfig target) {
        int missing = 0;
        for (int round = 0; round < MAX_SWEEP_ROUNDS; round++) {
            if (cancelRequested(jobId)) {
                rollBack(jobRepository.findById(jobId).orElseThrow());
                return -1;
            }
            missing = countMissing(appId, target);
            if (missing == 0) {
                return 0;
            }
            log.info("向量模型迁移补漏 jobId={} 第 {} 轮，缺 {} 项", jobId, round + 1, missing);
            sweepOnce(appId, target);
        }
        return countMissing(appId, target);
    }

    private int countMissing(Long appId, AiModelConfig target) {
        String tag = EmbeddingTag.of(target);
        return vectorStoreRepository.findDocumentIdsMissingTag(appId, tag).size()
                + vectorStoreRepository.findQaPairIdsMissingTag(appId, tag).size();
    }

    /** 把目标标签下缺失的文档 / QA 各补一遍，返回本轮处理的条目数。 */
    private int sweepOnce(Long appId, AiModelConfig target) {
        String tag = EmbeddingTag.of(target);
        List<Long> docs = vectorStoreRepository.findDocumentIdsMissingTag(appId, tag);
        List<Long> qas = vectorStoreRepository.findQaPairIdsMissingTag(appId, tag);
        for (Long docId : docs) {
            try {
                knowledgeService.reembedDocumentFor(docId, target);
            } catch (Exception e) {
                log.warn("补漏重嵌文档失败 docId={}: {}", docId, e.getMessage());
            }
        }
        for (Long qaId : qas) {
            try {
                qaPairService.reembedFor(qaId, target);
            } catch (Exception e) {
                log.warn("补漏重嵌 QA 失败 qaId={}: {}", qaId, e.getMessage());
            }
        }
        return docs.size() + qas.size();
    }

    /** 撤销一次切换：清掉目标标签下做了一半的向量、清空 pending，任务置 CANCELLED。 */
    private void rollBack(EmbeddingMigrationJob job) {
        tx.executeWithoutResult(s -> {
            applicationRepository.clearPendingEmbeddingModel(job.getAppId(), job.getToModelId());
            int removed = vectorStoreRepository.deleteByAppIdAndTag(job.getAppId().toString(), job.getToTag());
            jobRepository.transition(job.getId(),
                    Set.of(EmbeddingMigrationStatus.RUNNING, EmbeddingMigrationStatus.CANCELLING,
                            EmbeddingMigrationStatus.FAILED, EmbeddingMigrationStatus.INTERRUPTED),
                    EmbeddingMigrationStatus.CANCELLED, "已取消，应用仍在使用原模型", LocalDateTime.now());
            log.info("应用 {} 取消切换向量模型，清理目标模型向量 {} 条", job.getAppId(), removed);
        });
    }

    /** 进程重启打断的任务：标成 INTERRUPTED，pending 保留，界面上可「继续」或「取消」。 */
    @EventListener(ApplicationReadyEvent.class)
    public void markInterruptedOnStartup() {
        List<EmbeddingMigrationJob> stale = jobRepository.findByStatusIn(ACTIVE);
        for (EmbeddingMigrationJob job : stale) {
            transition(job.getId(), ACTIVE, EmbeddingMigrationStatus.INTERRUPTED, "服务重启打断了任务，可继续或取消", true);
        }
        if (!stale.isEmpty()) {
            log.warn("{} 个向量模型迁移任务被重启打断，已标为 INTERRUPTED", stale.size());
        }
    }

    private boolean cancelRequested(Long jobId) {
        return jobRepository.findById(jobId)
                .map(j -> j.getStatus() == EmbeddingMigrationStatus.CANCELLING)
                .orElse(true);
    }

    private void progress(Long jobId, int total, int done, int failed) {
        tx.executeWithoutResult(s -> jobRepository.updateProgress(jobId, total, done, failed));
    }

    private void transition(Long jobId, Set<EmbeddingMigrationStatus> expected, EmbeddingMigrationStatus to,
                            String message, boolean finished) {
        tx.executeWithoutResult(s -> jobRepository.transition(jobId, expected, to, message,
                finished ? LocalDateTime.now() : null));
    }

    private EmbeddingMigrationJob requireOwnedJob(Long jobId) {
        EmbeddingMigrationJob job = jobRepository.findById(jobId).orElse(null);
        // 不存在与无权一个口径（同重嵌进度的约定）
        if (job == null || !job.getAppId().equals(TenantContext.getAppId())
                || !TenantOwnershipGuard.mutableInCurrentContext(job.getTenantId(), job.getAppId())) {
            throw new BusinessException(404, "迁移任务不存在: " + jobId);
        }
        return job;
    }

    private Long requireAppId() {
        Long appId = TenantContext.getAppId();
        if (appId == null) {
            throw new InvalidOperationException("请先选择应用");
        }
        return appId;
    }
}
