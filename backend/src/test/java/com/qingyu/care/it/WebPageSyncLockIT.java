package com.qingyu.care.it;

import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试：{@code tryLockWebPageSync} 在<b>两条真实数据库连接</b>之间真的互斥。
 *
 * <p>存在的理由：同一文档并发同步会产出重复向量——A 删向量（未提交）→ B 的 DELETE 在
 * 自己的语句快照下看不到 A 刚插入的行、删 0 行 → 各插一份，{@code vector_store} 出现
 * 2N 行而 {@code chunk_count} 只记 N，检索时同一段落重复占用 topK。触发条件很日常：
 * 连点两次「立即重新抓取」，或调度器刚开跑时用户手点。
 *
 * <p>纯 Mockito 单测只能验证「服务尊重锁的返回值」（见 {@code WebPageSyncServiceTest} 的
 * 抢不到同步锁那条），验证不到「锁本身真的互斥」——那是 PostgreSQL 的行为，必须两条真实
 * 连接才能看见。两条测试合起来才构成完整防线。
 *
 * <p>不加类级 {@code @Transactional}：本 IT 要自己控制两个独立事务的边界与交叠时机。
 *
 * @author ruan
 */
class WebPageSyncLockIT extends AbstractPostgresIT {

    /** 咨询锁只认这个数字键，不要求文档真实存在，故用一个不会与别处相撞的值。 */
    private static final long DOC_ID = 987_654_321L;
    private static final long OTHER_DOC_ID = 987_654_322L;

    @Autowired DocumentRepository documentRepository;
    @Autowired PlatformTransactionManager transactionManager;

    /** 在一个独立事务里抢一次锁并立即提交。 */
    private boolean tryLockInOwnTransaction(long documentId) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager)
                .execute(status -> documentRepository.tryLockWebPageSync(documentId)));
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @Test
    void 另一个事务持锁期间抢不到_释放后又能抢到() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch mayRelease = new CountDownLatch(1);

        // 持锁方：在自己的事务里拿到锁后挂住，直到主线程验证完毕
        CompletableFuture<Boolean> holder = CompletableFuture.supplyAsync(() -> tx().execute(status -> {
            boolean got = documentRepository.tryLockWebPageSync(DOC_ID);
            acquired.countDown();
            try {
                mayRelease.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return got;
        }));

        assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();

        // 持锁期间：另一条连接抢同一篇必须失败，且必须【立即】返回 false 而不是阻塞等待
        // （阻塞会把调度线程一直占住；用 pg_try_ 前缀的非阻塞版本正是为此）
        assertThat(tryLockInOwnTransaction(DOC_ID)).isFalse();

        // 同一时刻抢【另一篇】不受影响——互斥是按文档粒度而不是全局串行
        assertThat(tryLockInOwnTransaction(OTHER_DOC_ID)).isTrue();

        mayRelease.countDown();
        assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();

        // 事务级锁：持锁事务一结束就自动释放，不留残留状态（这正是不用
        // "UPDATE status='PROCESSING' 抢占"的原因——那种写法进程崩在中途会永久卡死）
        assertThat(tryLockInOwnTransaction(DOC_ID)).isTrue();
    }

    @Test
    void 同一事务内重复抢同一篇不会自锁() {
        // 咨询锁对同一会话可重入。syncDocument 内部只抢一次，但万一将来调用链变成嵌套，
        // 不该在这里自己把自己锁死
        Boolean bothAcquired = tx().execute(status ->
                documentRepository.tryLockWebPageSync(DOC_ID)
                        && documentRepository.tryLockWebPageSync(DOC_ID));

        assertThat(bothAcquired).isTrue();
    }
}
