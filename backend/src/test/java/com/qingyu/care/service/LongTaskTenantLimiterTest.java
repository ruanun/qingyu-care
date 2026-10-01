package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 手动长任务按租户限并发（2026-09-30 安全审查 M6）。
 *
 * <p>要点是名额<b>在任务真正结束时</b>才归还：异步方法返回的 future 由 Spring 代理在方法跑完时完成，
 * 这里用手工控制的 future 模拟「任务还在跑」。
 *
 * @author ruan
 */
class LongTaskTenantLimiterTest {

    private final LongTaskTenantLimiter limiter = new LongTaskTenantLimiter();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(limiter, "maxPerTenant", 2);
        TenantContext.setTenantId(7L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 同一租户跑满上限后拒绝_任务结束才归还名额() {
        CompletableFuture<Void> a = new CompletableFuture<>();
        CompletableFuture<Void> b = new CompletableFuture<>();
        limiter.acquire().launch(() -> a);
        limiter.acquire().launch(() -> b);

        assertThatThrownBy(limiter::acquire)
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(429);

        a.complete(null);
        assertThat(limiter.runningCount(7L)).isEqualTo(1);
        limiter.acquire().launch(CompletableFuture::new);
    }

    @Test
    void 任务异常结束同样归还() {
        CompletableFuture<Void> a = new CompletableFuture<>();
        limiter.acquire().launch(() -> a);
        a.completeExceptionally(new RuntimeException("炸了"));

        assertThat(limiter.runningCount(7L)).isZero();
    }

    /** 防的就是「一个租户占满连带拒绝其他租户」：别的租户不受影响 */
    @Test
    void 不同租户各算各的() {
        limiter.acquire().launch(CompletableFuture::new);
        limiter.acquire().launch(CompletableFuture::new);

        TenantContext.setTenantId(8L);
        limiter.acquire().launch(CompletableFuture::new);
        assertThat(limiter.runningCount(8L)).isEqualTo(1);
    }

    @Test
    void 投递被拒时立刻归还() {
        assertThatThrownBy(() -> limiter.acquire().launch(() -> {
            throw new TaskRejectedException("队列满");
        })).isInstanceOf(TaskRejectedException.class);

        assertThat(limiter.runningCount(7L)).isZero();
    }

    /** 在事务里占名额、提交后才启动（AfterCommit）：回滚时启动不会发生，名额必须自己还回来 */
    @Test
    void 事务回滚且未启动时归还名额() {
        TransactionSynchronizationManager.initSynchronization();
        limiter.acquire();
        assertThat(limiter.runningCount(7L)).isEqualTo(1);

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        assertThat(limiter.runningCount(7L)).isZero();
    }

    @Test
    void 事务提交后已启动的任务不因提交回调提前归还() {
        TransactionSynchronizationManager.initSynchronization();
        LongTaskTenantLimiter.Permit permit = limiter.acquire();
        permit.launch(CompletableFuture::new);

        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        assertThat(limiter.runningCount(7L)).isEqualTo(1);
    }

    @Test
    void 上限为0即关闭() {
        ReflectionTestUtils.setField(limiter, "maxPerTenant", 0);
        for (int i = 0; i < 10; i++) {
            limiter.acquire().launch(CompletableFuture::new);
        }
        assertThat(limiter.runningCount(7L)).isZero();
    }
}
