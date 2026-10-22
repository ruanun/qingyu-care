package com.qingyu.care.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把「投递 {@code @Async} 任务」推迟到当前事务提交之后。
 *
 * <p><b>为什么需要它。</b>{@code @Async} 任务跑在另一个线程、走独立的数据库连接。若在
 * {@code @Transactional} 方法内部直接投递，异步线程可能在外层事务提交之前就被调度起来——
 * READ COMMITTED 隔离级别下它看不到本事务尚未提交的 INSERT，于是「按 id 回读刚建的那行」
 * 必然落空。这不是理论风险，本仓库为它付过两次代价：
 *
 * <ul>
 *   <li>站点爬取 C-1：{@code SiteCrawlService.startCrawl} 曾带 {@code @Transactional}，
 *       首次「新建站点并开爬」非确定性地立刻 FAILED「站点不存在」。当时的处理是
 *       <b>去掉</b> {@code @Transactional}（该方法只有一次写入，去掉即自动提交）。</li>
 *   <li>文件上传与网页建档：{@code uploadDocument} / {@code addWebPage} 同样在事务内投递，
 *       而异步侧取不到行时 {@code processDocument} 是 {@code orElse(null)} 后直接 return——
 *       <b>完全静默</b>，文档永远停在 PENDING、不入向量库、无日志无异常。</li>
 * </ul>
 *
 * <p><b>为什么不都照 C-1 那样去掉注解。</b>那两个方法去掉 {@code @Transactional} 也能修好
 * 这条竞态，但会顺带改掉它们的事务边界语义（读知识库做归属校验、写文档行，本来在同一个
 * 事务里）。用本类把投递挪到提交之后，是<b>只动投递时机、不动事务边界</b>的最小改法。
 *
 * <p>无活动事务时（例如 {@code reprocessDocument} 那种不带 {@code @Transactional} 的入口）
 * 直接同步执行，行为与原来完全一致。
 *
 * <p>⚠️ 新增「在事务里投递异步任务」的代码时，请用本类而不是直接调用；
 * {@code AsyncDispatchOutsideTransactionTest} 会扫描全部源码强制这条约定。
 *
 * @author ruan
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    /**
     * 当前事务提交后执行 {@code task}；没有活动事务时立即执行。
     *
     * <p>只在 {@code afterCommit} 阶段执行——回滚时不执行，这正是需要的语义：
     * 文档行没落库成功就不该有异步任务去处理它。
     */
    public static void run(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }
}
