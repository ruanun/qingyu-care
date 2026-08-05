package com.qingyu.care.service.media;

import com.qingyu.care.entity.ChatAttachment;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.ChatAttachmentRepository;
import com.qingyu.care.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 聊天附件编排：上传（校验 → 存储 → 落库）、关联消息、读取、孤儿清理。
 *
 * <p>上传与发消息分两步，故附件落库时 {@code messageId} 为 null，发消息时才回填。
 * 两个后果都在本类处理：{@link #attachToMessage} 做归属校验防越权引用，
 * {@link #cleanupOrphans} 清理「传了没发」的残留。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatAttachmentService {

    private final ChatAttachmentRepository attachmentRepository;
    private final FileService fileService;
    private final AttachmentValidator validator;

    @Transactional
    public ChatAttachment upload(String sessionId, MultipartFile file, MessageRole uploaderRole) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("读取上传文件失败");
        }

        String originalFilename = file.getOriginalFilename();
        AttachmentValidator.ValidatedFile v = validator.validate(bytes, originalFilename);
        String storageKey = fileService.uploadChatBytes(bytes, originalFilename, v.contentType());

        ChatAttachment attachment = ChatAttachment.builder()
                .sessionId(sessionId)
                .fileName(originalFilename)
                .fileSize((long) bytes.length)
                .contentType(v.contentType())
                .storageKey(storageKey)
                .width(v.width())
                .height(v.height())
                .uploaderRole(uploaderRole)
                .build();
        return attachmentRepository.save(attachment);
    }

    /**
     * 把附件挂到消息上。三道校验缺一不可——少了任何一道，
     * 攻击者都能遍历 id 把别人的附件挂到自己的消息上。
     *
     * <p>注意 findById 本身不受 Hibernate @Filter 保护（本仓库的成文教训），
     * 所以必须显式比对 sessionId，不能只靠"查得到就是我的"。
     */
    @Transactional
    public void attachToMessage(List<Long> attachmentIds, String sessionId, Long messageId) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        for (Long id : attachmentIds) {
            ChatAttachment a = attachmentRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("附件不存在: " + id));
            if (!sessionId.equals(a.getSessionId())) {
                throw new BusinessException("附件不属于当前会话");
            }
            if (a.getMessageId() != null) {
                throw new BusinessException("附件已被使用");
            }
            a.setMessageId(messageId);
            attachmentRepository.save(a);
        }
    }

    /** 读取用：归属校验交给调用方（两条访问端点的鉴权路径不同） */
    public ChatAttachment getForRead(Long id) {
        return attachmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("附件不存在: " + id));
    }

    /**
     * 清理孤儿附件：始终没被任何消息引用、且超过给定小时数的。
     * 单个失败不影响其余（fail-soft），返回真正清理成功的条数。
     *
     * <p>S3 对象必须一起删——只删库行会留下永久占用空间、且再也查不到的孤儿对象。
     */
    @Transactional
    public int cleanupOrphans(int hours) {
        List<ChatAttachment> orphans = attachmentRepository
                .findByMessageIdIsNullAndCreatedAtBefore(LocalDateTime.now().minusHours(hours));
        int cleaned = 0;
        for (ChatAttachment a : orphans) {
            try {
                fileService.deleteFile(a.getStorageKey());
                attachmentRepository.delete(a);
                cleaned++;
            } catch (Exception e) {
                log.warn("清理孤儿附件失败 id={} key={}: {}", a.getId(), a.getStorageKey(), e.getMessage());
            }
        }
        return cleaned;
    }
}
