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

    /**
     * 批量取消息的附件——历史消息一次查完，避免逐条 LAZY 的 N+1。
     * 按 id 升序（近似上传顺序）：不排序时同一条消息带多张图，渲染顺序由 PG 返回顺序决定，
     * 两次刷新可能不同（M-3）。
     */
    List<ChatAttachment> findByMessageIdInOrderById(Collection<Long> messageIds);

    /** 该会话已上传的附件数（含尚未挂到消息上的），供单会话上限判断。 */
    long countBySessionId(String sessionId);

    /**
     * 孤儿：始终没被任何消息引用、且早于给定时间创建。带 Pageable 分批取——
     * 积压深时一次性拉全部会把巨量实体读进持久化上下文、再串行做巨量 S3 网络删除（同
     * WebhookRetryScheduler/findDueWebPageIds 的既定分批范式）。
     */
    List<ChatAttachment> findByMessageIdIsNullAndCreatedAtBefore(LocalDateTime before, Pageable pageable);
}
