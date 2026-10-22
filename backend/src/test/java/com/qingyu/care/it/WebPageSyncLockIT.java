package com.qingyu.care.it;

import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试：{@code tryLockDocumentProcessing} 在<b>两条真实数据库连接</b>之间真的互斥。
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
    @Autowired JdbcTemplate jdbcTemplate;

    /** 在一个独立事务里抢一次锁并立即提交。 */
    private boolean tryLockInOwnTransaction(long documentId) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager)
                .execute(status -> documentRepository.tryLockDocumentProcessing(documentId)));
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * 整个数据库上、键为 {@code documentId} 的咨询锁行数——0 就是「真的释放干净了」。
     *
     * <p>{@code pg_try_advisory_xact_lock(bigint)} 的单参形态在 {@code pg_locks} 里表现为
     * {@code classid} = 键的高 32 位、{@code objid} = 低 32 位、{@code objsubid} = 1；
     * 本 IT 用的键小于 2^31，故高位恒为 0。{@code objid} 是 oid 类型，显式转 bigint 再比。
     */
    private long advisoryLockCount(long documentId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_locks
                WHERE locktype = 'advisory' AND classid = 0 AND objid::bigint = ? AND objsubid = 1
                """, Long.class, documentId);
        return count == null ? 0 : count;
    }

    @Test
    void 另一个事务持锁期间抢不到_释放后又能抢到() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch mayRelease = new CountDownLatch(1);

        // 持锁方：在自己的事务里拿到锁后挂住，直到主线程验证完毕
        CompletableFuture<Boolean> holder = CompletableFuture.supplyAsync(() -> tx().execute(status -> {
            boolean got = documentRepository.tryLockDocumentProcessing(DOC_ID);
            acquired.countDown();
            try {
                mayRelease.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return got;
        }));

        assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();

        // 先确认这把锁在 pg_locks 里确实看得见——下面「释放后为 0」那条断言的非空前提，
        // 否则一个永远查不到行的 SQL 也能让它无条件通过
        assertThat(advisoryLockCount(DOC_ID)).isGreaterThanOrEqualTo(1);

        // 持锁期间：另一条连接抢同一篇必须失败，且必须【立即】返回 false 而不是阻塞等待
        // （阻塞会把调度线程一直占住；用 pg_try_ 前缀的非阻塞版本正是为此）
        assertThat(tryLockInOwnTransaction(DOC_ID)).isFalse();

        // 同一时刻抢【另一篇】不受影响——互斥是按文档粒度而不是全局串行
        assertThat(tryLockInOwnTransaction(OTHER_DOC_ID)).isTrue();

        mayRelease.countDown();
        assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();

        // 事务级锁：持锁事务一结束就【由数据库】释放，不留残留状态（这正是不用
        // "UPDATE status='PROCESSING' 抢占"的原因——那种写法进程崩在中途会永久卡死）。
        //
        // ⚠️ 直接查 pg_locks，而不是只靠下面那次"又抢得到"来推断：把 SQL 里的
        // pg_try_advisory_xact_lock 退化成【会话级】的 pg_try_advisory_lock（连接归还
        // 连接池后不释放，是本条 SQL 最危险的一种改坏法），下面那次重抢仍有很大概率为
        // true——HikariCP 倾向把刚归还的连接优先发出去，主线程多半拿到的就是持锁方那条
        // 连接，同一 session 可重入。也就是说那条断言实际上是连接池调度的函数，不是锁
        // 语义的函数；这一条才是。
        assertThat(advisoryLockCount(DOC_ID)).isZero();

        assertThat(tryLockInOwnTransaction(DOC_ID)).isTrue();
    }

    @Test
    void 同一事务内重复抢同一篇不会自锁() {
        // 咨询锁对同一会话可重入。这条不是假设性的：syncDocument 抢一次外层锁后，
        // 内部调用的 processDocument 会在同一事务里再抢同一把（见 KnowledgeServiceImpl），
        // 自锁的话网页同步会整条断掉
        Boolean bothAcquired = tx().execute(status ->
                documentRepository.tryLockDocumentProcessing(DOC_ID)
                        && documentRepository.tryLockDocumentProcessing(DOC_ID));

        assertThat(bothAcquired).isTrue();
    }
}
