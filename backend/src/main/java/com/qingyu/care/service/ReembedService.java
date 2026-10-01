package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.entity.QaPair;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.QaPairStatus;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.QaPairRepository;
import com.qingyu.care.util.SecurityUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import com.qingyu.care.config.AsyncConfig;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 向量重建（换 embedding 模型迁移）：按范围重嵌 active 文档 + QA 到当前模型空间并打标。
 * 每条 delete+重嵌幂等（复用 {@link KnowledgeService#processDocument(Long)} /
 * {@link QaPairService#reembed(QaPair)}）；{@code @Async} 跑，内存进度（重启不留存，
 * 幂等重跑即续）；单条 fail-soft，不中断整批。
 *
 * @author ruan
 */
@Service
@Slf4j
public class ReembedService {

    /** 重建范围：ALL=全平台（仅平台管理员）/ APP=当前应用下全部知识库 / KB=单个知识库。 */
    public enum Scope { ALL, APP, KB }

    /** 内存进度（重启不留存；幂等重跑即续）。 */
    public static final class Progress {
        public final String jobId;
        public final String scope;
        public volatile int total;
        public final AtomicInteger done = new AtomicInteger();
        public final AtomicInteger failed = new AtomicInteger();
        public volatile String status;
        public final LocalDateTime startedAt = LocalDateTime.now();
        public volatile LocalDateTime finishedAt;
        /** 发起任务的租户（平台管理员发起为 null）。private 且无 getter：不进响应体 */
        private final Long tenantId;

        Progress(String jobId, String scope) {
            this(jobId, scope, null);
        }

        Progress(String jobId, String scope, Long tenantId) {
            this.jobId = jobId;
            this.scope = scope;
            this.tenantId = tenantId;
            this.status = "RUNNING";
        }
    }

    private final KnowledgeService knowledgeService;
    private final QaPairService qaPairService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final QaPairRepository qaPairRepository;

    // 自注入解决 @Async 同类调用不生效问题（同 KnowledgeServiceImpl 范式）
    private ReembedService self;

    public ReembedService(KnowledgeService knowledgeService,
                           QaPairService qaPairService,
                           KnowledgeBaseRepository knowledgeBaseRepository,
                           DocumentRepository documentRepository,
                           QaPairRepository qaPairRepository) {
        this.knowledgeService = knowledgeService;
        this.qaPairService = qaPairService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.qaPairRepository = qaPairRepository;
    }

    @Autowired
    @Lazy
    public void setSelf(ReembedService self) {
        this.self = self;
    }


    private LongTaskTenantLimiter longTaskLimiter;

    /** setter 注入：构造函数被多条单测手工调用，见 LongTaskTenantLimiter。没注入会 NPE 而不是静默跳过限流。 */
    @Autowired
    public void setLongTaskLimiter(LongTaskTenantLimiter longTaskLimiter) {
        this.longTaskLimiter = longTaskLimiter;
    }

    private final ConcurrentHashMap<String, Progress> jobs = new ConcurrentHashMap<>();

    /** 触发重嵌，返回 jobId（异步执行）。范围解析 + 归属校验在请求线程同步完成，越权/参数缺失直接抛异常。 */
    public String startReembed(Scope scope, Long knowledgeBaseId) {
        List<KnowledgeBase> kbs = resolveKbs(scope, knowledgeBaseId);
        // 先占名额再登记任务：超限时不留一条永远 RUNNING 的进度（2026-09-30 安全审查 M6）
        LongTaskTenantLimiter.Permit permit = longTaskLimiter.acquire();
        String jobId = UUID.randomUUID().toString();
        Progress p = new Progress(jobId, scope.name(), TenantContext.getTenantId());
        jobs.put(jobId, p);
        List<Long> kbIds = kbs.stream().map(KnowledgeBase::getId).toList();
        permit.launch(() -> self.runReembed(p, kbIds));
        return jobId;
    }

    public Progress getProgress(String jobId) {
        Progress p = jobs.get(jobId);
        // 只能查自己租户发起的任务；平台管理员可查全部。不存在与无权一律 404，不泄露 jobId 是否存在
        // （2026-09-30 安全审查：此前任何登录用户拿到 jobId 都能看别人的进度）
        boolean visible = p != null && (SecurityUtil.isPlatformAdmin()
                || (p.tenantId != null && p.tenantId.equals(TenantContext.getTenantId())));
        if (!visible) {
            throw new BusinessException(404, "重建任务不存在: " + jobId);
        }
        return p;
    }

    /** 范围解析 + 归属校验（请求线程，依赖 TenantContext）。 */
    private List<KnowledgeBase> resolveKbs(Scope scope, Long knowledgeBaseId) {
        return switch (scope) {
            case ALL -> {
                if (!SecurityUtil.isPlatformAdmin()) {
                    throw new BusinessException(403, "全平台重建仅限平台管理员");
                }
                yield knowledgeBaseRepository.findAll();
            }
            case APP -> {
                Long appId = TenantContext.getAppId();
                if (appId == null) {
                    throw new BusinessException(400, "未选择应用");
                }
                yield knowledgeBaseRepository.findByAppId(appId);
            }
            case KB -> {
                KnowledgeBase kb = knowledgeService.getKnowledgeBase(knowledgeBaseId); // 归属校验（越权抛）
                yield List.of(kb);
            }
        };
    }

    /** 异步执行：遍历 KB → COMPLETED 文档重嵌 + ACTIVE QA 重嵌；单条 fail-soft，不中断整批。 */
    @Async(AsyncConfig.LONG_TASK_EXECUTOR)
    public CompletableFuture<Void> runReembed(Progress p, List<Long> kbIds) {
        try {
            List<Document> docs = kbIds.stream()
                    .flatMap(kbId -> documentRepository.findByKnowledgeBaseIdAndStatus(kbId, DocumentStatus.COMPLETED).stream())
                    .toList();
            List<QaPair> qas = kbIds.stream()
                    .flatMap(kbId -> qaPairRepository.findByKnowledgeBaseIdAndStatus(kbId, QaPairStatus.ACTIVE).stream())
                    .toList();
            p.total = docs.size() + qas.size();
            for (Document d : docs) {
                try {
                    knowledgeService.processDocument(d.getId());
                    // processDocument 内部吞异常并置 FAILED（不重抛），故回读状态判定成败
                    Document after = documentRepository.findById(d.getId()).orElse(null);
                    if (after != null && after.getStatus() == DocumentStatus.FAILED) {
                        p.failed.incrementAndGet();
                        log.warn("文档重嵌失败(状态 FAILED) id={}", d.getId());
                    } else {
                        p.done.incrementAndGet();
                    }
                } catch (Exception e) {
                    // processDocument 理论上不重抛，但保留兜底（如回读时异常）
                    p.failed.incrementAndGet();
                    log.warn("文档重嵌异常 id={}: {}", d.getId(), e.getMessage());
                }
            }
            for (QaPair q : qas) {
                try {
                    qaPairService.reembed(q);
                    p.done.incrementAndGet();
                } catch (Exception e) {
                    p.failed.incrementAndGet();
                    log.warn("QA 重嵌失败 id={}: {}", q.getId(), e.getMessage());
                }
            }
            p.status = "COMPLETED";
        } catch (Exception e) {
            p.status = "FAILED";
            log.error("重建任务异常 jobId={}: {}", p.jobId, e.getMessage(), e);
        } finally {
            p.finishedAt = LocalDateTime.now();
        }
        // 返回 future 是为了让 LongTaskTenantLimiter 在任务真正结束时归还名额
        return CompletableFuture.completedFuture(null);
    }
}
