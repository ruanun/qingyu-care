package com.qingyu.care.entity;

import com.qingyu.care.enums.ReaderType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 「某个读者在某个会话里读到哪条消息」的水位线。一个会话下坐席与访客各一行，互不干扰。
 *
 * <p>⚠️ {@code readerId} <b>绝不能为 null</b>：PostgreSQL 唯一约束里 {@code NULL != NULL}，
 * {@code (session_id, 'VISITOR', NULL)} 可插入任意多行，唯一约束形同虚设，水位线变成多行并存、
 * 读出来哪一行全看运气——且不报错，只表现为已读状态间歇性错乱。访客用 {@code chat_session.id}
 * 作 {@code readerId}。
 *
 * @author ruan
 */
@Entity
@Table(name = "message_read_receipt",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_receipt_session_reader",
           columnNames = {"session_id", "reader_type", "reader_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class MessageReadReceipt extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reader_type", nullable = false, length = 20)
    private ReaderType readerType;

    /** 见类注释：绝不能为 null。 */
    @Column(name = "reader_id", nullable = false)
    private Long readerId;

    @Column(name = "up_to_message_id", nullable = false)
    private Long upToMessageId;

    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;
}
