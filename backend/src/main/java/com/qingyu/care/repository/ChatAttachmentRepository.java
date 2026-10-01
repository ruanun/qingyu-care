package com.qingyu.care.repository;

import com.qingyu.care.entity.ChatAttachment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * 某应用自 {@code since} 起访客（{@code uploader_role = 'USER'}）上传的附件数与总字节，返回单行 [count, bytes]。
     * 显式按 app_id 收窄而不依赖过滤器：调用方在开放 API 上下文里，但口径必须是「这个应用」而不是「当前过滤器恰好开着什么」。
     */
    @Query(value = "SELECT count(*), COALESCE(SUM(file_size), 0) FROM chat_attachment "
            + "WHERE app_id = :appId AND uploader_role = 'USER' AND created_at >= :since", nativeQuery = true)
    List<Object[]> visitorUsageSince(@Param("appId") Long appId, @Param("since") LocalDateTime since);

    /**
     * 孤儿：始终没被任何消息引用、且早于给定时间创建。带 Pageable 分批取——
     * 积压深时一次性拉全部会把巨量实体读进持久化上下文、再串行做巨量 S3 网络删除（同
     * WebhookRetryScheduler/findDueWebPageIds 的既定分批范式）。
     */
    List<ChatAttachment> findByMessageIdIsNullAndCreatedAtBefore(LocalDateTime before, Pageable pageable);
}
