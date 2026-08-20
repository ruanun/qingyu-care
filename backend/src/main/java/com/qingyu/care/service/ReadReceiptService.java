package com.qingyu.care.service;

import com.qingyu.care.entity.MessageReadReceipt;
import com.qingyu.care.enums.ReaderType;
import com.qingyu.care.repository.MessageReadReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 已读水位线的读写。水位线语义：{@code upToMessageId} 表示「读到这条为止（含）」，只增不减。
 *
 * <p>⚠️ <b>{@link #markRead} 刻意不加方法级 {@code @Transactional}</b>：首次回执走「UPDATE 0 行 →
 * insert」，而并发首次回执会撞唯一键；若整个方法在一个事务里，那次 {@code DataIntegrityViolationException}
 * 会把事务标成 rollback-only，catch 住也救不回来。让 UPDATE 与 insert 各走独立事务，冲突时重试
 * 一次 UPDATE 即可真正提交——同 {@code ApiUsageService.record} 的既有范式。
 *
 * <p>⚠️ <b>若调用方自身已在一个事务里，改调 {@link #markReadInNewTransaction} 而非
 * {@link #markRead}</b>：同一次 insert 唯一键冲突若发生在调用方的事务内，PostgreSQL 会把<b>那个
 * 外层事务</b>标成 aborted（25P02），本类内部的重试 UPDATE 同样会失败，catch 住也救不回——同
 * {@code searchKnowledge} 走 {@code REQUIRES_NEW} 只读事务、聊天附件
 * {@code attachInNewTransaction} 那两个坑同一个形状。两个公开方法共享同一段私有实现
 * {@link #doMarkRead}，互不经对方调用——避免了「同类内部直调不经代理、方法自身的
 * {@code @Transactional} 静默失效」这个本仓库另有先例的坑（{@code KnowledgeServiceImpl}/
 * {@code WebPageSyncService} 的 self 自注入范式）：{@code doMarkRead} 本身不带任何事务注解，
 * 谁调用它就在谁的事务边界内执行，因此两个公开方法各自被外部（跨 bean）调用时都能拿到
 * 自己声明的事务语义，不需要也不应该再引入 self 自注入。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
public class ReadReceiptService {

    private final MessageReadReceiptRepository repository;

    /**
     * 推进水位线，返回<b>生效后</b>的值。调用方若自身已在事务中，改用
     * {@link #markReadInNewTransaction}——见类注释。
     *
     * <p>返回值可能大于 {@code upToMessageId}——并发下别的标签页已经推得更远，此时不回退。
     */
    public long markRead(Long sessionId, ReaderType readerType, Long readerId, Long upToMessageId) {
        return doMarkRead(sessionId, readerType, readerId, upToMessageId);
    }

    /**
     * 语义与 {@link #markRead} 完全一致，唯一差别是整段逻辑跑在一个全新的独立事务
     * （{@code REQUIRES_NEW}）里：调用方自身若也在事务中（如
     * {@code ChatServiceImpl.acceptSession}），insert 撞唯一键只会回滚这个新事务，
     * 不会连累调用方的外层事务——见类注释。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long markReadInNewTransaction(Long sessionId, ReaderType readerType, Long readerId, Long upToMessageId) {
        return doMarkRead(sessionId, readerType, readerId, upToMessageId);
    }

    private long doMarkRead(Long sessionId, ReaderType readerType, Long readerId, Long upToMessageId) {
        int updated = repository.advanceWatermark(
                sessionId, readerType.name(), readerId, upToMessageId, LocalDateTime.now());
        if (updated > 0) {
            return upToMessageId;
        }

        // 0 行有两种情况：该行还不存在（需 insert），或传入值不比现值大（不回退）
        Optional<MessageReadReceipt> existing =
                repository.findBySessionIdAndReaderTypeAndReaderId(sessionId, readerType, readerId);
        if (existing.isPresent()) {
            return existing.get().getUpToMessageId();
        }

        try {
            repository.save(MessageReadReceipt.builder()
                    .sessionId(sessionId)
                    .readerType(readerType)
                    .readerId(readerId)
                    .upToMessageId(upToMessageId)
                    .readAt(LocalDateTime.now())
                    .build());
            return upToMessageId;
        } catch (DataIntegrityViolationException conflict) {
            // 并发首次回执：另一方刚插进去。重试 UPDATE——本方法不带事务注解，谁调用它就在谁的
            // 事务边界内执行（见类注释）。
            // ⚠️ 两个调用方的实际结果并不对称：markRead 场景下调用方（markAgentRead/
            // markVisitorRead）自身不带 @Transactional，上面的 insert 与这里的重试 UPDATE
            // 各自独立开关自己的仓储事务，insert 失败不连累重试，此重试能真正提交。
            // 但 markReadInNewTransaction 场景下，insert 与这次重试同属 REQUIRES_NEW 开出的
            // 同一个物理事务——insert 撞唯一键已经让这个事务落入 PG 的 25P02 aborted 状态，
            // 同一事务内的任何后续语句（包括这次重试）都会跟着失败，异常会从
            // markReadInNewTransaction 里抛出，交给调用方（acceptSession）的 fail-soft catch
            // 兜底吞掉——这是预期行为，不是本方法的缺陷。
            repository.advanceWatermark(
                    sessionId, readerType.name(), readerId, upToMessageId, LocalDateTime.now());
            return currentOrDefault(sessionId, readerType, readerId, upToMessageId);
        }
    }

    /** 当前水位线；从无回执时返回 {@code null}（区别于 0——0 是合法的「什么都没读」）。 */
    public Long currentWatermark(Long sessionId, ReaderType readerType, Long readerId) {
        return repository.findBySessionIdAndReaderTypeAndReaderId(sessionId, readerType, readerId)
                .map(MessageReadReceipt::getUpToMessageId)
                .orElse(null);
    }

    private long currentOrDefault(Long sessionId, ReaderType readerType, Long readerId, Long fallback) {
        Long current = currentWatermark(sessionId, readerType, readerId);
        return current != null ? current : fallback;
    }
}
