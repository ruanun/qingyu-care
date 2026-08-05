package com.qingyu.care.service.media;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.ChatAttachment;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.ChatAttachmentRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.FileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
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
    private ChatSessionRepository sessionRepository;
    @Mock
    private FileService fileService;
    @Mock
    private AttachmentValidator validator;
    @InjectMocks
    private ChatAttachmentService service;

    @AfterEach
    void clearTenantContext() {
        // TenantContext 是进程内共享的 ThreadLocal；跨租户相关用例会设置它，
        // 不清理会污染同一线程后续用例（尤其是断言失败提前退出、finally 未跑到时）。
        TenantContext.clear();
    }

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

    /** upload() 的 sessionId 归属校验用：一个存在的会话，tenantId 由调用方指定（null 表示无归属）。 */
    private static ChatSession session(Long tenantId, Long appId) {
        ChatSession s = new ChatSession();
        s.setTenantId(tenantId);
        s.setAppId(appId);
        return s;
    }

    // ---------- attachToMessage：归属校验 ----------

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
    void 关联消息时附件跨租户则拒绝() {
        ChatAttachment a = attachment(7L, "s-1", null);
        a.setTenantId(999L);   // 属于别的租户
        when(attachmentRepository.findById(7L)).thenReturn(Optional.of(a));
        TenantContext.setTenantId(1L);

        // 与「查不到」同语义（防枚举探测），不是 BusinessException
        assertThatThrownBy(() -> service.attachToMessage(List.of(7L), "s-1", 100L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void 关联消息时同租户附件可正常关联() {
        ChatAttachment a = attachment(7L, "s-1", null);
        a.setTenantId(1L);
        when(attachmentRepository.findById(7L)).thenReturn(Optional.of(a));
        TenantContext.setTenantId(1L);

        service.attachToMessage(List.of(7L), "s-1", 100L);

        assertThat(a.getMessageId()).isEqualTo(100L);
    }

    @Test
    void 合法附件被回填messageId() {
        ChatAttachment a = attachment(7L, "s-1", null);
        when(attachmentRepository.findById(7L)).thenReturn(Optional.of(a));

        service.attachToMessage(List.of(7L), "s-1", 100L);

        assertThat(a.getMessageId()).isEqualTo(100L);
        verify(attachmentRepository).save(same(a));
    }

    @Test
    void 附件为空列表时什么都不做() {
        service.attachToMessage(List.of(), "s-1", 100L);
        service.attachToMessage(null, "s-1", 100L);

        verifyNoInteractions(attachmentRepository);
    }

    // ---------- attachToMessage：入参与批量边界 ----------

    @Test
    void 关联消息时会话标识为空则拒绝() {
        assertThatThrownBy(() -> service.attachToMessage(List.of(7L), null, 100L))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(attachmentRepository);
    }

    @Test
    void 关联消息时消息id为空则拒绝() {
        assertThatThrownBy(() -> service.attachToMessage(List.of(7L), "s-1", null))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(attachmentRepository);
    }

    @Test
    void 关联消息时重复id去重只处理一次() {
        ChatAttachment a = attachment(7L, "s-1", null);
        when(attachmentRepository.findById(7L)).thenReturn(Optional.of(a));

        service.attachToMessage(List.of(7L, 7L), "s-1", 100L);

        verify(attachmentRepository, times(1)).findById(7L);
        verify(attachmentRepository, times(1)).save(same(a));
    }

    @Test
    void 关联消息附件数超过上限则拒绝() {
        List<Long> tooMany = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

        assertThatThrownBy(() -> service.attachToMessage(tooMany, "s-1", 100L))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(attachmentRepository);
    }

    @Test
    void 多个合法附件都被回填并分别save() {
        ChatAttachment a1 = attachment(7L, "s-1", null);
        ChatAttachment a2 = attachment(8L, "s-1", null);
        when(attachmentRepository.findById(7L)).thenReturn(Optional.of(a1));
        when(attachmentRepository.findById(8L)).thenReturn(Optional.of(a2));

        service.attachToMessage(List.of(7L, 8L), "s-1", 100L);

        assertThat(a1.getMessageId()).isEqualTo(100L);
        assertThat(a2.getMessageId()).isEqualTo(100L);
        verify(attachmentRepository).save(same(a1));
        verify(attachmentRepository).save(same(a2));
    }

    @Test
    void 批量关联中途失败时前面已处理的仍会调用save() {
        ChatAttachment a1 = attachment(7L, "s-1", null);
        ChatAttachment a2 = attachment(8L, "别人的会话", null);   // 第二个校验会失败
        when(attachmentRepository.findById(7L)).thenReturn(Optional.of(a1));
        when(attachmentRepository.findById(8L)).thenReturn(Optional.of(a2));

        assertThatThrownBy(() -> service.attachToMessage(List.of(7L, 8L), "s-1", 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("附件不属于当前会话");

        // 纯 Mockito 单测测不到真实的事务回滚：id=7 在循环内确已被 save 一次，
        // 但方法上的 @Transactional 保证异常传播出去后整批一起回滚、id=7 最终不落库。
        // 「不落库」这一步需要 @DataJpaTest/IT 才能完整验证；本用例只钉住 mock 层面
        // 可验证的部分——循环真的处理到了 id=7 才在 id=8 处失败，而不是提前中断。
        verify(attachmentRepository).save(same(a1));
        verify(attachmentRepository, never()).save(same(a2));
    }

    // ---------- validateAttachable（C-1：streamChat 流开始前的预校验）----------

    @Test
    void 预校验时会话不匹配则拒绝() {
        when(attachmentRepository.findById(7L))
                .thenReturn(Optional.of(attachment(7L, "别人的会话", null)));

        assertThatThrownBy(() -> service.validateAttachable(List.of(7L), "我的会话"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("附件不属于当前会话");
    }

    @Test
    void 预校验时附件不存在则拒绝() {
        when(attachmentRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateAttachable(List.of(7L), "s-1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 预校验时附件跨租户则拒绝() {
        ChatAttachment a = attachment(7L, "s-1", null);
        a.setTenantId(999L);
        when(attachmentRepository.findById(7L)).thenReturn(Optional.of(a));
        TenantContext.setTenantId(1L);

        assertThatThrownBy(() -> service.validateAttachable(List.of(7L), "s-1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void 预校验时会话标识为空则拒绝() {
        assertThatThrownBy(() -> service.validateAttachable(List.of(7L), null))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(attachmentRepository);
    }

    @Test
    void 预校验附件数超过上限则拒绝() {
        List<Long> tooMany = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

        assertThatThrownBy(() -> service.validateAttachable(tooMany, "s-1"))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(attachmentRepository);
    }

    @Test
    void 预校验附件为空列表时什么都不做() {
        service.validateAttachable(List.of(), "s-1");
        service.validateAttachable(null, "s-1");

        verifyNoInteractions(attachmentRepository);
    }

    @Test
    void 预校验通过时不做任何写操作() {
        ChatAttachment a = attachment(7L, "s-1", null);
        when(attachmentRepository.findById(7L)).thenReturn(Optional.of(a));

        service.validateAttachable(List.of(7L), "s-1");

        verify(attachmentRepository, never()).save(any());
    }

    /**
     * 预校验刻意不检查"是否已被占用"——占用状态在预校验与真正落库之间的窗口内仍可能变化
     * （见 validateAttachable javadoc）。这是它与 attachToMessage 唯一的校验差异，用一条
     * 用例钉死：已被别的消息占用的附件仍能通过预校验（不抛异常）。
     */
    @Test
    void 预校验不检查占用状态_已占用的附件也能通过() {
        ChatAttachment a = attachment(7L, "s-1", 999L);   // 已挂在别的消息 999 上
        when(attachmentRepository.findById(7L)).thenReturn(Optional.of(a));

        assertThatCode(() -> service.validateAttachable(List.of(7L), "s-1"))
                .doesNotThrowAnyException();
    }

    // ---------- upload ----------

    @Test
    void 上传时落库的contentType宽高全部取自validator不取客户端声明值() {
        byte[] bytes = "fake-image-bytes".getBytes();
        MultipartFile file = new MockMultipartFile("file", "a.png", "image/gif", bytes); // 客户端谎报 gif
        AttachmentValidator.ValidatedFile validated =
                new AttachmentValidator.ValidatedFile("image/png", true, 100, 200);        // 服务端探测出的真实类型
        when(sessionRepository.findBySessionId("s-1")).thenReturn(Optional.of(session(null, null)));
        when(validator.validate(bytes, "a.png")).thenReturn(validated);
        when(fileService.uploadChatBytes(bytes, "a.png", "image/png")).thenReturn("chat/2026/x.png");
        when(attachmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.upload("s-1", file, MessageRole.USER);

        verify(validator).validate(bytes, "a.png");
        ArgumentCaptor<ChatAttachment> captor = ArgumentCaptor.forClass(ChatAttachment.class);
        verify(attachmentRepository).save(captor.capture());
        assertThat(captor.getValue().getContentType()).isEqualTo("image/png");
        assertThat(captor.getValue().getWidth()).isEqualTo(100);
        assertThat(captor.getValue().getHeight()).isEqualTo(200);
    }

    @Test
    void 上传时校验失败则不上传不落库() {
        byte[] bytes = "bad".getBytes();
        MultipartFile file = new MockMultipartFile("file", "a.exe", "application/octet-stream", bytes);
        when(sessionRepository.findBySessionId("s-1")).thenReturn(Optional.of(session(null, null)));
        when(validator.validate(bytes, "a.exe")).thenThrow(new BusinessException("不支持的文件类型：exe"));

        assertThatThrownBy(() -> service.upload("s-1", file, MessageRole.USER))
                .isInstanceOf(BusinessException.class);

        verify(fileService, never()).uploadChatBytes(any(), any(), any());
        verify(attachmentRepository, never()).save(any());
    }

    @Test
    void 上传时文件大小超过20MB硬闸门直接拒绝不读字节() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(21L * 1024 * 1024);

        assertThatThrownBy(() -> service.upload("s-1", file, MessageRole.USER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("20MB");

        verify(file, never()).getBytes();
        verifyNoInteractions(validator, fileService, attachmentRepository);
    }

    @Test
    void 上传落库失败时回滚已上传的S3对象() {
        byte[] bytes = "fake-image-bytes".getBytes();
        MultipartFile file = new MockMultipartFile("file", "a.png", "image/png", bytes);
        AttachmentValidator.ValidatedFile validated =
                new AttachmentValidator.ValidatedFile("image/png", true, 100, 200);
        when(sessionRepository.findBySessionId("s-1")).thenReturn(Optional.of(session(null, null)));
        when(validator.validate(bytes, "a.png")).thenReturn(validated);
        when(fileService.uploadChatBytes(bytes, "a.png", "image/png")).thenReturn("chat/2026/x.png");
        when(attachmentRepository.save(any())).thenThrow(new RuntimeException("DB 挂了"));

        assertThatThrownBy(() -> service.upload("s-1", file, MessageRole.USER))
                .isInstanceOf(RuntimeException.class);

        verify(fileService).deleteFile("chat/2026/x.png");
    }

    // ---------- upload：sessionId 归属校验（I-2）----------

    @Test
    void 上传时sessionId为空白则拒绝且不落库() {
        MultipartFile file = new MockMultipartFile("file", "a.png", "image/png", "x".getBytes());

        // chat_attachment.session_id 是 NOT NULL，空白会话上传只能落成 ""——一个所有租户/
        // 应用共享的命名空间：既让下载端的 sessionId 相等校验对该批附件形同虚设（同 app 内
        // 任何调用方带 ?sessionId= 空值就能命中），又因 attachToMessage 要求精确相等而永远
        // 挂不上真实会话，是一条只能等孤儿清理的死数据。故空白必须拒绝，不能放行。
        assertThatThrownBy(() -> service.upload("  ", file, MessageRole.USER))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(sessionRepository, validator, fileService, attachmentRepository);
    }

    @Test
    void 上传时sessionId指向不存在的会话则拒绝且不落库() {
        MultipartFile file = new MockMultipartFile("file", "a.png", "image/png", "x".getBytes());
        when(sessionRepository.findBySessionId("no-such-session")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload("no-such-session", file, MessageRole.USER))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(validator, fileService, attachmentRepository);
    }

    @Test
    void 上传时sessionId属于其他租户则拒绝且不落库() {
        MultipartFile file = new MockMultipartFile("file", "a.png", "image/png", "x".getBytes());
        when(sessionRepository.findBySessionId("s-tenant-2")).thenReturn(Optional.of(session(2L, null)));
        TenantContext.setTenantId(1L); // 当前调用方是租户 1，会话属于租户 2

        // 与「查不到」同语义（防枚举探测），不是 BusinessException 字面 400
        assertThatThrownBy(() -> service.upload("s-tenant-2", file, MessageRole.USER))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(validator, fileService, attachmentRepository);
    }

    @Test
    void 上传时sessionId属于本租户则放行() {
        byte[] bytes = "fake-image-bytes".getBytes();
        MultipartFile file = new MockMultipartFile("file", "a.png", "image/png", bytes);
        AttachmentValidator.ValidatedFile validated =
                new AttachmentValidator.ValidatedFile("image/png", true, 100, 200);
        when(sessionRepository.findBySessionId("s-tenant-1")).thenReturn(Optional.of(session(1L, null)));
        when(validator.validate(bytes, "a.png")).thenReturn(validated);
        when(fileService.uploadChatBytes(bytes, "a.png", "image/png")).thenReturn("chat/2026/x.png");
        when(attachmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        TenantContext.setTenantId(1L);

        ChatAttachment saved = service.upload("s-tenant-1", file, MessageRole.USER);

        assertThat(saved).isNotNull();
    }

    // ---------- cleanupOrphans ----------

    @Test
    void 清理孤儿会同时删除S3对象而不是只删库行() {
        ChatAttachment orphan = attachment(1L, "s-1", null);
        orphan.setStorageKey("knowledge/chat/2026/08/01/x.png");
        when(attachmentRepository.findByMessageIdIsNullAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(orphan));
        when(fileService.deleteFile("knowledge/chat/2026/08/01/x.png")).thenReturn(true);

        int n = service.cleanupOrphans(24);

        assertThat(n).isEqualTo(1);
        verify(fileService).deleteFile("knowledge/chat/2026/08/01/x.png");
        verify(attachmentRepository).delete(same(orphan));
    }

    @Test
    void 清理时单个S3删除失败不影响其余() {
        ChatAttachment bad = attachment(1L, "s-1", null);
        bad.setStorageKey("bad-key");   // 与 good 区分：默认都是 "k"，共用会导致下面的 stub 连坐 good
        ChatAttachment good = attachment(2L, "s-1", null);
        good.setStorageKey("good-key");
        when(attachmentRepository.findByMessageIdIsNullAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(bad, good));
        // 真实场景：FileService.deleteFile 内部吞掉所有异常回 false，从不抛——这才是
        // C-1 要处理的真实分支，比打桩 doThrow 更贴近生产行为
        when(fileService.deleteFile("bad-key")).thenReturn(false);
        when(fileService.deleteFile("good-key")).thenReturn(true);

        int n = service.cleanupOrphans(24);

        assertThat(n).isEqualTo(1);                                  // 只算成功的
        verify(attachmentRepository, never()).delete(same(bad));    // 失败的保留库行，下轮重试
        verify(attachmentRepository).delete(same(good));            // 后一个照常清理
    }

    @Test
    void 清理时S3删除抛异常也不影响其余() {
        // 防御性路径：即便 deleteFile 违反自己「从不抛异常」的契约意外抛出（如未预期的
        // RuntimeException），try/catch 仍要兜住，不能让一条孤儿的意外拖垮整轮清理
        ChatAttachment bad = attachment(1L, "s-1", null);
        bad.setStorageKey("bad-key");
        ChatAttachment good = attachment(2L, "s-1", null);
        good.setStorageKey("good-key");
        when(attachmentRepository.findByMessageIdIsNullAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(bad, good));
        doThrow(new RuntimeException("S3 挂了")).when(fileService).deleteFile("bad-key");
        when(fileService.deleteFile("good-key")).thenReturn(true);

        int n = service.cleanupOrphans(24);

        assertThat(n).isEqualTo(1);
        verify(attachmentRepository, never()).delete(same(bad));
        verify(attachmentRepository).delete(same(good));
    }

    @Test
    void 清理孤儿时先删S3对象后删库行顺序不可颠倒() {
        ChatAttachment orphan = attachment(1L, "s-1", null);
        orphan.setStorageKey("k1");
        when(attachmentRepository.findByMessageIdIsNullAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of(orphan));
        when(fileService.deleteFile("k1")).thenReturn(true);

        service.cleanupOrphans(24);

        InOrder inOrder = inOrder(fileService, attachmentRepository);
        inOrder.verify(fileService).deleteFile("k1");
        inOrder.verify(attachmentRepository).delete(same(orphan));
    }

    @Test
    void 清理孤儿的截止时间等于now减去24小时() {
        when(attachmentRepository.findByMessageIdIsNullAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of());

        service.cleanupOrphans(24);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(attachmentRepository).findByMessageIdIsNullAndCreatedAtBefore(captor.capture(), any());
        assertThat(captor.getValue()).isCloseTo(LocalDateTime.now().minusHours(24), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void 清理孤儿的截止时间随hours参数变化为1小时() {
        when(attachmentRepository.findByMessageIdIsNullAndCreatedAtBefore(any(), any()))
                .thenReturn(List.of());

        service.cleanupOrphans(1);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(attachmentRepository).findByMessageIdIsNullAndCreatedAtBefore(captor.capture(), any());
        assertThat(captor.getValue()).isCloseTo(LocalDateTime.now().minusHours(1), within(5, ChronoUnit.SECONDS));
    }
}
