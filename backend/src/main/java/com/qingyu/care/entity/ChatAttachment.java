package com.qingyu.care.entity;

import com.qingyu.care.enums.MessageRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 聊天消息附件。上传与发消息分两步，故 {@code messageId} 可空——
 * 上传时为 null，发消息时才回填；始终为 null 的是「传了没发」的孤儿，由定时任务清理。
 *
 * @author ruan
 */
@Entity
@Table(name = "chat_attachment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatAttachment extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属消息。上传时为 null，发消息时回填 */
    @Column(name = "message_id")
    private Long messageId;

    /** 会话业务标识（ChatSession.sessionId，不是主键 id），上传时即绑定 */
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** 原始文件名，展示用——渲染时必须转义 */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** 服务端探测出的真实 MIME，不是客户端传的 Content-Type */
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    /** S3 对象键。存 key 不存 URL——裸 URL 客户端用不了，存了是误导 */
    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    /** 仅图片，供前端预留占位避免加载时布局跳动 */
    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Enumerated(EnumType.STRING)
    @Column(name = "uploader_role", nullable = false, length = 20)
    private MessageRole uploaderRole;

    /**
     * 创建时间。孤儿清理按它筛选，故必须有——
     * BaseTenantEntity 只提供 tenantId/appId，不含时间戳字段。
     */
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
