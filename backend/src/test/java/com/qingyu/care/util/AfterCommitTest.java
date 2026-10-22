package com.qingyu.care.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AfterCommit} 的单元测试——不需要数据库，直接操作 Spring 的事务同步管理器。
 *
 * <p>守的是两件事：有活动事务时<b>必须推迟</b>（这是它存在的全部理由，提前执行等于没修那条
 * 竞态）；没有活动事务时<b>必须立即执行</b>（否则 {@code reprocessDocument} 那类不带
 * {@code @Transactional} 的入口会静默地什么都不做——比原来的缺陷更糟）。
 *
 * @author ruan
 */
class AfterCommitTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("有活动事务时推迟到 afterCommit 才执行，注册那一刻不执行")
    void defers_until_after_commit_when_transaction_is_active() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger runs = new AtomicInteger();

        AfterCommit.run(runs::incrementAndGet);

        // 关键断言：注册完还没跑。跑早了就等于没修那条「异步线程读不到未提交行」的竞态
        assertThat(runs.get()).as("注册时就执行了，等于没有推迟").isZero();

        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        synchronizations.forEach(TransactionSynchronization::afterCommit);
        assertThat(runs.get()).as("afterCommit 阶段没有执行").isEqualTo(1);
    }

    @Test
    @DisplayName("回滚（只走 afterCompletion、不走 afterCommit）时不执行")
    void does_not_run_when_transaction_rolls_back() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger runs = new AtomicInteger();

        AfterCommit.run(runs::incrementAndGet);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        // 这正是想要的语义：文档行没落库成功，就不该有异步任务去处理它
        assertThat(runs.get()).as("回滚了却把异步任务投出去了").isZero();
    }

    @Test
    @DisplayName("无活动事务时立即执行——不能静默丢掉")
    void runs_immediately_without_transaction() {
        AtomicInteger runs = new AtomicInteger();

        AfterCommit.run(runs::incrementAndGet);

        assertThat(runs.get())
                .as("没有事务时被静默丢掉了，那比原来的缺陷更糟——调用方会以为任务已投递")
                .isEqualTo(1);
    }
}
