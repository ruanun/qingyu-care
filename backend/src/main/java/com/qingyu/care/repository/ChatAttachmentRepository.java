package com.qingyu.care.repository;

import com.qingyu.care.entity.ChatAttachment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * @author ruan
 */
public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, Long> {

    /** 批量取消息的附件——历史消息一次查完，避免逐条 LAZY 的 N+1 */
    List<ChatAttachment> findByMessageIdIn(Collection<Long> messageIds);

    /**
     * 孤儿：始终没被任何消息引用、且早于给定时间创建。带 Pageable 分批取——
     * 积压深时一次性拉全部会把巨量实体读进持久化上下文、再串行做巨量 S3 网络删除（同
     * WebhookRetryScheduler/findDueWebPageIds 的既定分批范式）。
     */
    List<ChatAttachment> findByMessageIdIsNullAndCreatedAtBefore(LocalDateTime before, Pageable pageable);
}
