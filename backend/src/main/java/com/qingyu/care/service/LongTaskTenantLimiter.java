package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 手动触发的长任务按租户限并发（2026-09-30 安全审查 M6）。
 *
 * <p>长任务池（{@code AsyncConfig.LONG_TASK_EXECUTOR}，固定 6 线程 + 500 队列）是全平台共享的，而重嵌、QA 生成、
 * 重新处理、添加网页、立即同步此前都没有按租户的上限——一个租户管理员连点几次「全量重嵌」或批量提交几百个慢站点，
 * 6 个线程就被占满，其它租户的上传摄取排队几小时；队列满后投递被拒，文档永久停在 PENDING。站点爬取早有
 * 「全局 + 按租户」两层额度（{@code SiteCrawlService.launch}），本类把租户那一层推广到其余入口。
 *
 * <p>用法：请求线程 {@link #acquire()} 占名额（超限抛 429），再用 {@link Permit#launch} 启动返回
 * {@link CompletableFuture} 的 {@code @Async} 方法——Spring 的异步代理在方法<b>真正跑完</b>时才完成这个 future，
 * 名额就在那时归还，不用在每个方法体里写 finally。在事务里占名额时，事务回滚且任务没启动会自动归还
 * （配合 {@code AfterCommit.run} 把启动推迟到提交之后）。
 *
 * <p>已知边界：
 * <ul>
 *   <li>计数在进程内，多实例部署时是「每实例每租户」上限（与站点爬取同口径）；</li>
 *   <li>普通文件上传不受限——批量上传是正常操作，拒掉体验太差；它有存储配额与单文件大小两道约束；</li>
 *   <li>平台管理员（无 tenantId）共用一个桶。</li>
 * </ul>
 *
 * @author ruan
 */
@Component
public class LongTaskTenantLimiter {

    private static final Long PLATFORM_BUCKET = -1L;

    /** 每个租户同时在跑（含排队）的手动长任务上限；0 = 关闭 */
    @Value("${qingyu.care.async.long-task-max-per-tenant:3}")
    private int maxPerTenant;

    private final ConcurrentHashMap<Long, AtomicInteger> running = new ConcurrentHashMap<>();

    /** 为当前租户占一个名额；超限抛 429。必须在请求线程调用（依赖 TenantContext）。 */
    public Permit acquire() {
        if (maxPerTenant <= 0) {
            return new Permit(null);
        }
        Long tenantId = TenantContext.getTenantId();
        AtomicInteger counter = running.computeIfAbsent(tenantId == null ? PLATFORM_BUCKET : tenantId,
                k -> new AtomicInteger());
        if (counter.incrementAndGet() > maxPerTenant) {
            counter.decrementAndGet();
            throw new BusinessException(429, "后台处理任务较多，请等当前任务完成后再试");
        }
        Permit permit = new Permit(counter);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    // 回滚时 AfterCommit 里的启动不会执行，名额要在这里还回去
                    if (status != STATUS_COMMITTED && !permit.launched.get()) {
                        permit.release();
                    }
                }
            });
        }
        return permit;
    }

    /** 当前租户正在占用的名额数，仅供测试与排查。 */
    int runningCount(Long tenantId) {
        AtomicInteger c = running.get(tenantId == null ? PLATFORM_BUCKET : tenantId);
        return c == null ? 0 : c.get();
    }

    /** 一个已占用的名额。归还是幂等的。 */
    public static final class Permit {

        private final AtomicInteger counter;
        private final AtomicBoolean launched = new AtomicBoolean(false);
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Permit(AtomicInteger counter) {
            this.counter = counter;
        }

        /**
         * 启动任务，任务结束（含异常）时归还名额。{@code task} 必须调用返回 {@link CompletableFuture} 的 {@code @Async}
         * 方法——返回 void 的话代理立即返回，名额会在任务真正开始前就被还掉，等于没限。
         */
        public void launch(Supplier<? extends CompletableFuture<?>> task) {
            launched.set(true);
            CompletableFuture<?> future;
            try {
                future = task.get();
            } catch (RuntimeException e) {
                // 投递被拒（队列满）等：任务没起来，名额立刻还
                release();
                throw e;
            }
            if (future == null) {
                release();
            } else {
                future.whenComplete((r, e) -> release());
            }
        }

        void release() {
            if (counter != null && released.compareAndSet(false, true)) {
                counter.decrementAndGet();
            }
        }
    }
}
