package com.qingyu.care.repository;

import com.qingyu.care.entity.MessageReadReceipt;
import com.qingyu.care.enums.ReaderType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * @author ruan
 */
@Repository
public interface MessageReadReceiptRepository extends JpaRepository<MessageReadReceipt, Long> {

    /**
     * 水位线只增不减地推进，返回受影响行数（0 表示该行不存在需 insert，或传入值不比现值大）。
     *
     * <p><b>不能先读后写</b>：坐席开两个标签页并发上报、或 SSE 乱序到达时，先读后写会让水位线
     * 回退，对端界面上「已读」会闪一下消失。带 {@code up_to_message_id < :upTo} 条件的原子
     * UPDATE 让并发自然收敛到最大值。
     *
     * <p>{@code @Modifying} 更新必须在事务中执行；调用方 {@code ReadReceiptService.markRead}
     * 刻意不加方法级事务（避免唯一键冲突把整方法标记 rollback-only），故在此让本方法各走独立
     * 事务——同 {@code SysApiUsageRepository.incrementUsage} 的既有范式。
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE message_read_receipt SET up_to_message_id = :upTo, read_at = :now "
            + "WHERE session_id = :sessionId AND reader_type = :readerType AND reader_id = :readerId "
            + "AND up_to_message_id < :upTo", nativeQuery = true)
    int advanceWatermark(@Param("sessionId") Long sessionId,
                         @Param("readerType") String readerType,
                         @Param("readerId") Long readerId,
                         @Param("upTo") Long upTo,
                         @Param("now") LocalDateTime now);

    Optional<MessageReadReceipt> findBySessionIdAndReaderTypeAndReaderId(
            Long sessionId, ReaderType readerType, Long readerId);
}
