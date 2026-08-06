package com.qingyu.care.repository;

import com.qingyu.care.entity.Document;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.repository.projection.DocIdNameRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByKnowledgeBaseId(Long knowledgeBaseId);

    List<Document> findByStatus(DocumentStatus status);

    List<Document> findByKnowledgeBaseIdAndStatus(Long knowledgeBaseId, DocumentStatus status);

    // ===== 覆盖率分析（native 绕过租户过滤器，where 手写）=====

    /** 窗口内零命中的启用文档（最多 50 条；status 字面量以 DocumentStatus 实际枚举为准） */
    @Query(value = """
            SELECT d.id AS id, d.name AS name FROM document d
            WHERE (:tenantId IS NULL OR d.tenant_id = :tenantId)
              AND (:appId IS NULL OR d.app_id = :appId)
              AND (d.enabled IS NULL OR d.enabled = TRUE)
              AND d.status = 'COMPLETED'
              AND NOT EXISTS (
                  SELECT 1 FROM stat_doc_hit_daily h
                  WHERE h.document_id = d.id AND h.stat_date >= :from)
            ORDER BY d.id
            LIMIT 50
            """, nativeQuery = true)
    List<DocIdNameRow> findZeroHitDocs(@Param("from") LocalDate from,
                                       @Param("tenantId") Long tenantId,
                                       @Param("appId") Long appId);

    /** 按 id 批量取文档名（native 免过滤器：排行含跨应用文档时名称仍可回填） */
    @Query(value = "SELECT d.id AS id, d.name AS name FROM document d WHERE d.id IN (:ids)", nativeQuery = true)
    List<DocIdNameRow> findNamesByIds(@Param("ids") List<Long> ids);

    /**
     * 抢占一篇文档的「处理互斥锁」：抢到返回 true，已被<b>另一个事务</b>持有则立即
     * 返回 false（不阻塞）。锁随当前事务结束自动释放。
     *
     * <p>解决的问题：同一文档被并发重嵌会产出重复向量。两个线程各自事务，A 先
     * {@code deleteByDocumentId} 删向量（未提交），B 的 DELETE 撞行锁等待；A 插入新 chunk
     * 并提交后 B 继续，但 B 的 DELETE 在自己的语句快照下看不到 A 刚插入的行，删 0 行、
     * 再插一份——{@code vector_store} 出现 2N 行而 {@code chunk_count} 只记 N，检索时同一
     * 段落重复占用 topK。触发条件很日常：连点两次「立即重新抓取」，或换 embedding 模型后
     * 跑全量重嵌批任务时调度器恰好也在同步同一篇。
     *
     * <p>⚠️ 真正的临界区是 {@link com.qingyu.care.service.KnowledgeService#processDocument}
     * （先删旧向量、再插新向量），它有<b>三个</b>入口：网页同步、重嵌批任务
     * （{@code ReembedService}）、手动重新处理（{@code reprocessDocument}）。所以锁抢在
     * 它自己的首行，而不是只在某一个调用方那里——只锁住其中一条，另外两条照样能并发进入。
     * {@code WebPageSyncService.syncDocument} 在外层另抢一次，把「抓取 + 比对 hash + 重嵌 +
     * 落 contentHash」整段一起互斥；咨询锁对同一事务可重入，内外两次不会自锁。
     *
     * <p>为什么用 PostgreSQL 事务级咨询锁而不是另外两种写法：
     * <ul>
     *   <li><b>不是 document 行锁</b>（{@code SELECT ... FOR UPDATE}）：会引入 ABBA 死锁——
     *       {@code processDocument} 的顺序是「先删 vector_store、再更新 document」，若调用方
     *       改成「先锁 document、再删 vector_store」，两个方向恰好相反。
     *       ⚠️ 这里免于死锁的理由<b>不是</b>"咨询锁不在行锁的锁图里"：咨询锁是真正的重量级锁，
     *       同样参与 PostgreSQL 的死锁检测。真正的理由是本方法用的是<b>非阻塞</b>的
     *       {@code pg_try_} 版本——抢不到立刻返回 false，永不进入等待图，故不可能成为死锁环的
     *       一环。将来若有人换成阻塞版 {@code pg_advisory_xact_lock}，「T1 持咨询锁等行锁 /
     *       T2 持行锁等咨询锁」立刻可以成环，本结论随即失效。</li>
     *   <li><b>不是 {@code UPDATE ... SET status='PROCESSING'} 抢占</b>：会与
     *       {@code processDocument} 自己维护的 status 状态机打架，且进程崩在中途时文档会
     *       永久卡在 PROCESSING、此后再也同步不了——凭空多出一条静默失败路径。咨询锁由
     *       数据库在事务结束（含连接断开）时无条件释放，不留残留状态。</li>
     * </ul>
     *
     * <p>⚠️ 必须在有活动事务时调用（{@code xact} 即事务级）：无事务时它会在自动提交的
     * 迷你事务里拿了就放，互斥静默失效。两个调用方
     * （{@link com.qingyu.care.service.KnowledgeService#processDocument} 与
     * {@link com.qingyu.care.service.web.WebPageSyncService#syncDocument}）各自的
     * {@code @Transactional} 是这条前提，动它们的代理链路时务必一并复核。
     *
     * <p>⚠️ 咨询锁的键空间是整库共享的。当前全库只有这一个用途（文档处理互斥，可 grep
     * {@code advisory} 复核），故直接用 document id 作键；将来若出现第二种用途，两边必须
     * 显式划分键空间。
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:documentId)", nativeQuery = true)
    boolean tryLockDocumentProcessing(@Param("documentId") Long documentId);

    /**
     * 查到期需要自动同步的网页文档 id（最多 50 条，逻辑同 {@link #findZeroHitDocs}）。
     *
     * <p>用原生 SQL 绕过 Hibernate 租户过滤器，与本文件 {@link #findZeroHitDocs}/
     * {@link #findNamesByIds} 手法一致——但对调度器线程而言这是<b>纵深防御</b>而非唯一活路：
     * 调度器线程没有 Sa-Token 会话，{@code TenantDataAspect} 在「未登录」分支直接 return、
     * 根本不会启用任何过滤器（见该类第 49 行 {@code !isLoggedIn()} 分支），所以就算写成
     * 普通 JPQL，当前实现下也一样能查到全部租户的到期文档；{@code tenant_id = -1}
     * 那一支只在「已登录的非管理员却缺租户上下文」时才会触发，不是调度器线程会走到的路径。
     * 选 native 是为了不依赖「调度器线程永远没有 Sa-Token 会话」这个前提本身——万一将来
     * 这段逻辑被复用到某个带会话的上下文里，也不会静默退化成被过滤，而不至于重蹈本方法本身
     * 曾经历过的教训（见下方 IT 里记录的变异自证过程）。
     *
     * <p>到期条件：类型为 URL、周期非 MANUAL、且（从未同步过 或 距上次成功同步已超过周期）。
     *
     * <p>{@code :now} 显式 {@code CAST(... AS timestamp)}：JDBC 占位符不带类型信息，
     * 直接写 {@code :now - INTERVAL '1 hour'} 时 PostgreSQL 会把参数解析成 interval
     * 而非 timestamp，导致 {@code timestamp <= interval} 报「operator does not exist」；
     * 显式转型消除歧义。
     *
     * <p>{@code LIMIT 50}：单线程调度池（Spring Boot 默认 {@code spring.task.scheduling.pool.size=1}）
     * 上同时挂着 webhook 队列轮询、在线坐席心跳等其它定时任务，若到期文档过多、逐篇
     * {@link com.qingyu.care.service.web.WebPageSyncService#syncDocument} 又是同步 HTTP 抓取，
     * 无上界会长时间占住这唯一线程；每小时一轮，超出上限的留到下一轮，不丢数据。
     */
    @Query(value = """
            SELECT d.id AS id FROM document d
            WHERE d.document_type = 'URL'
              AND d.sync_interval <> 'MANUAL'
              AND (
                    d.last_synced_at IS NULL
                 OR (d.sync_interval = 'HOURLY' AND d.last_synced_at <= CAST(:now AS timestamp) - INTERVAL '1 hour')
                 OR (d.sync_interval = 'DAILY'  AND d.last_synced_at <= CAST(:now AS timestamp) - INTERVAL '1 day')
                 OR (d.sync_interval = 'WEEKLY' AND d.last_synced_at <= CAST(:now AS timestamp) - INTERVAL '7 days')
              )
            ORDER BY d.last_synced_at NULLS FIRST, d.id
            LIMIT 50
            """, nativeQuery = true)
    List<Long> findDueWebPageIds(@Param("now") LocalDateTime now);

    /**
     * 按知识库 + 源地址找已存在的文档，供爬取判断"这一页是否已建过档"。
     *
     * <p>⚠️ 用 {@code findFirst} 而不是返回单个结果的 {@code findBy...}：第一期的
     * {@code addWebPage} 没有去重校验，存量库可能已有同 URL 的重复文档，
     * 用单结果查询会在那些库上抛 {@code IncorrectResultSizeDataAccessException}
     * 把整轮爬取打断。加 {@code ORDER BY id} 保证选中的是最早那篇、结果稳定。
     */
    Optional<Document> findFirstByKnowledgeBaseIdAndSourceUrlOrderByIdAsc(Long knowledgeBaseId, String sourceUrl);

    /** 某爬取站点下的全部文档，供删除站点时连带处理。 */
    List<Document> findByCrawlSiteId(Long crawlSiteId);
}
