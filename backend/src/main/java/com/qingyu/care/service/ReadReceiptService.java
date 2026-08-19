package com.qingyu.care.service;

import com.qingyu.care.entity.MessageReadReceipt;
import com.qingyu.care.enums.ReaderType;
import com.qingyu.care.repository.MessageReadReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 已读水位线的读写。水位线语义：{@code upToMessageId} 表示「读到这条为止（含）」，只增不减。
 *
 * <p>⚠️ <b>本类刻意不加方法级 {@code @Transactional}</b>：首次回执走「UPDATE 0 行 → insert」，
 * 而并发首次回执会撞唯一键；若整个方法在一个事务里，那次 {@code DataIntegrityViolationException}
 * 会把事务标成 rollback-only，catch 住也救不回来。让 UPDATE 与 insert 各走独立事务，冲突时重试
 * 一次 UPDATE 即可真正提交——同 {@code ApiUsageService.record} 的既有范式。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
public class ReadReceiptService {

    private final MessageReadReceiptRepository repository;

    /**
     * 推进水位线，返回<b>生效后</b>的值。
     *
     * <p>返回值可能大于 {@code upToMessageId}——并发下别的标签页已经推得更远，此时不回退。
     */
    public long markRead(Long sessionId, ReaderType readerType, Long readerId, Long upToMessageId) {
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
            // 并发首次回执：另一方刚插进去。重试 UPDATE——未加方法级事务，此重试能真正提交。
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
