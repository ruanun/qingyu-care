package com.qingyu.care.service.media;

import com.qingyu.care.entity.ChatAttachment;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.ChatAttachmentRepository;
import com.qingyu.care.service.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class ChatAttachmentServiceTest {

    @Mock
    private ChatAttachmentRepository attachmentRepository;
    @Mock
    private FileService fileService;
    @Mock
    private AttachmentValidator validator;
    @InjectMocks
    private ChatAttachmentService service;

    private static ChatAttachment attachment(Long id, String sessionId, Long messageId) {
        ChatAttachment a = ChatAttachment.builder()
                .sessionId(sessionId)
                .messageId(messageId)
                .fileName("a.png")
                .fileSize(10L)
                .contentType("image/png")
                .storageKey("k")
                .uploaderRole(MessageRole.USER)
                .build();
        a.setId(id);
        return a;
    }

    @Test
    void 关联消息时会话不匹配则拒绝() {
        when(attachmentRepository.findById(7L))
                .thenReturn(Optional.of(attachment(7L, "别人的会话", null)));

        assertThatThrownBy(() -> service.attachToMessage(List.of(7L), "我的会话", 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("附件不属于当前会话");

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void 关联消息时附件已被别的消息占用则拒绝() {
        when(attachmentRepository.findById(7L))
                .thenReturn(Optional.of(attachment(7L, "s-1", 999L)));   // 已挂在 999 上

        assertThatThrownBy(() -> service.attachToMessage(List.of(7L), "s-1", 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("附件已被使用");

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void 关联消息时附件不存在则拒绝() {
        when(attachmentRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.attachToMessage(List.of(7L), "s-1", 100L))
                .isInstanceOf(BusinessException.class);

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void 合法附件被回填messageId() {
        ChatAttachment a = attachment(7L, "s-1", null);
        when(attachmentRepository.findById(7L)).thenReturn(Optional.of(a));

        service.attachToMessage(List.of(7L), "s-1", 100L);

        assertThat(a.getMessageId()).isEqualTo(100L);
        verify(attachmentRepository).save(a);
    }

    @Test
    void 附件为空列表时什么都不做() {
        service.attachToMessage(List.of(), "s-1", 100L);
        service.attachToMessage(null, "s-1", 100L);

        verifyNoInteractions(attachmentRepository);
    }

    @Test
    void 清理孤儿会同时删除S3对象而不是只删库行() {
        ChatAttachment orphan = attachment(1L, "s-1", null);
        orphan.setStorageKey("knowledge/chat/2026/08/01/x.png");
        when(attachmentRepository.findByMessageIdIsNullAndCreatedAtBefore(any()))
                .thenReturn(List.of(orphan));

        int n = service.cleanupOrphans(24);

        assertThat(n).isEqualTo(1);
        verify(fileService).deleteFile("knowledge/chat/2026/08/01/x.png");
        verify(attachmentRepository).delete(orphan);
    }

    @Test
    void 清理时单个S3删除失败不影响其余() {
        ChatAttachment bad = attachment(1L, "s-1", null);
        bad.setStorageKey("bad-key");   // 与 good 区分：默认都是 "k"，共用会导致下面的 stub 连坐 good
        ChatAttachment good = attachment(2L, "s-1", null);
        good.setStorageKey("good-key");
        when(attachmentRepository.findByMessageIdIsNullAndCreatedAtBefore(any()))
                .thenReturn(List.of(bad, good));
        doThrow(new RuntimeException("S3 挂了")).when(fileService).deleteFile(bad.getStorageKey());

        int n = service.cleanupOrphans(24);

        assertThat(n).isEqualTo(1);                  // 只算成功的
        verify(attachmentRepository).delete(good);   // 后一个照常清理
    }
}
