package com.qingyu.care.service.media;

import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.entity.ChatAttachment;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.MessageRole;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.exception.ResourceNotFoundException;
import com.qingyu.care.repository.ChatAttachmentRepository;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
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

    /**
     * 上传硬闸门（绝对上界），早于 {@link AttachmentValidator} 的 5MB/20MB 细粒度限额生效——
     * 不设它会先把整个文件完整读进堆才被拒，并发几个超大文件即可打崩堆，与解压炸弹是
     * 同一伤害模式，只是入口更早。20MB 与 validator 的非图片文件上限对齐，为绝对上界。
     */
    private static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;

    /** 单条消息最多携带的附件数，防止无上限的 id 列表拖慢 attachToMessage 的逐条 findById/save */
    private static final int MAX_ATTACHMENTS_PER_MESSAGE = 9;

    /** 孤儿清理单轮批量上限，避免积压很深时一次性把全部孤儿读进持久化上下文再串行做网络删除 */
    private static final int CLEANUP_BATCH_SIZE = 200;

    private final ChatAttachmentRepository attachmentRepository;
    private final ChatSessionRepository sessionRepository;
    private final FileService fileService;
    private final AttachmentValidator validator;

    public ChatAttachment upload(String sessionId, MultipartFile file, MessageRole uploaderRole) {
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BusinessException("文件不能超过 20MB");
        }
        requireOwnedSession(sessionId);
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
        try {
            return attachmentRepository.save(attachment);
        } catch (RuntimeException e) {
            // S3 对象已经真传上去了，库落不下就得手动回滚——否则它成了一个没有任何库行
            // 指向的泄漏对象，cleanupOrphans 是按 chat_attachment 表查孤儿的，够不到它。
            log.warn("附件落库失败，回滚已上传的 S3 对象 key={}: {}", storageKey, e.getMessage());
            fileService.deleteFile(storageKey);
            throw e;
        }
    }

    /**
     * sessionId 归属校验：传了就必须指向一个当前上下文能写的会话，越权/不存在与「不存在」
     * 同语义处理（防枚举探测）。故意不要求必须传——widget 允许「先选图再发第一条消息」，
     * 那一刻会话尚未创建，硬性要求会打死这个交互，因此 sessionId 为 null/空白时直接放行。
     *
     * <p>不校验会放行两个后果：其一，管理端这条上传端点没有任何限流（开放 API 那条还有
     * 60/min），配上 24h 的孤儿保留期，任意已登录成员可以对着一个压根不存在/别人的会话
     * 无限次传 20MB 文件，是不受限的存储通道；其二，库里会堆出指向不存在或他人会话的脏行。
     *
     * <p>不直接复用 {@code ChatServiceImpl.getSession}（做的是同一件事）——避免与
     * {@code ChatServiceImpl} 形成循环依赖（后续会话消息关联会反过来让 ChatServiceImpl
     * 调用本类把附件挂上新消息），故在此复刻同一套「{@code findBySessionId} 不受 Hibernate
     * {@code @Filter} 保护、加载后须显式校验归属」的既定模式（同 {@code ChatServiceImpl#getSession}）。
     *
     * <p>用写语义的 {@link TenantOwnershipGuard#mutableInCurrentContext}
     * 而非读语义——上传相当于往目标会话名下追加数据，语义上更接近「写」，
     * 且与 {@link #attachToMessage} 对附件本身的归属判定保持同一严格度。
     */
    private void requireOwnedSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        ChatSession session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("会话", sessionId));
        if (!TenantOwnershipGuard.mutableInCurrentContext(session.getTenantId(), session.getAppId())) {
            throw new ResourceNotFoundException("会话", sessionId);
        }
    }

    /**
     * 把附件挂到消息上。四道校验缺一不可：
     * <ol>
     *   <li>存在性——找不到直接当「不存在」处理；</li>
     *   <li>租户归属——{@code findById} 不受 Hibernate {@code @Filter} 保护（本仓库的成文教训），
     *       不校验就能跨租户枚举 id 引用别人的附件，与「查不到」同语义处理，兼防枚举探测；</li>
     *   <li>会话归属——同租户内也不能引用别的会话的附件；</li>
     *   <li>占用状态——不能把同一个附件挂到两条消息上。</li>
     * </ol>
     *
     * <p>整批附件走同一个事务：中途任一附件校验失败，前面已 {@code save} 的也随之回滚，
     * 避免出现「一条消息只挂上一半附件」的半成品状态。
     */
    @Transactional
    public void attachToMessage(List<Long> attachmentIds, String sessionId, Long messageId) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        if (sessionId == null) {
            throw new BusinessException("会话标识不能为空");
        }
        if (messageId == null) {
            throw new BusinessException("消息 id 不能为空");
        }
        List<Long> distinctIds = attachmentIds.stream().distinct().toList();
        if (distinctIds.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
            throw new BusinessException("单条消息最多携带 " + MAX_ATTACHMENTS_PER_MESSAGE + " 个附件");
        }
        for (Long id : distinctIds) {
            ChatAttachment a = attachmentRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("附件不存在: " + id));
            if (!TenantOwnershipGuard.mutableInCurrentContext(a.getTenantId(), a.getAppId())) {
                throw new ResourceNotFoundException("附件不存在: " + id);
            }
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
     * 清理孤儿附件：始终没被任何消息引用、且超过给定小时数的。单个失败不影响其余（fail-soft），
     * 返回真正清理成功的条数。单轮最多处理 {@value #CLEANUP_BATCH_SIZE} 条，积压更深时留给
     * 下一轮调度接着清，不在一轮里把全部孤儿读进内存、串行发起网络删除。
     *
     * <p>S3 对象必须一起删——只删库行会留下永久占用空间、且再也查不到的孤儿对象。
     * {@link FileService#deleteFile} 内部会吞掉所有异常并返回 {@code false}（只有 {@link Error}
     * 能逃逸），从不抛受检异常——必须显式检查它的返回值，否则「S3 对象没删掉、库行却照删」
     * 的情况会被无视返回值的调用悄悄计成功，S3 上留下再也没有库行指向的垃圾对象。
     *
     * <p>本方法故意不用 {@code @Transactional} 包住整个循环：每条附件的清理在语义上是独立的，
     * 跨条目共享一个事务反而有害——{@code SimpleJpaRepository.delete} 自带
     * {@code @Transactional}，以 {@code PROPAGATION_REQUIRED} 参与外层事务；DB 侧任一条删除
     * 失败，异常从参与型事务逃逸会触发 {@code globalRollbackOnParticipationFailure}（默认
     * true）让整批回滚——但 S3 侧此时已经真删了、没有事务能把它拉回来，就会出现「库行复活
     * 但对象已经不在」的不一致。逐条各自独立提交，才能让「单条失败不影响其余」名副其实。
     */
    public int cleanupOrphans(int hours) {
        List<ChatAttachment> orphans = attachmentRepository.findByMessageIdIsNullAndCreatedAtBefore(
                LocalDateTime.now().minusHours(hours), PageRequest.of(0, CLEANUP_BATCH_SIZE));
        int cleaned = 0;
        for (ChatAttachment a : orphans) {
            try {
                if (!fileService.deleteFile(a.getStorageKey())) {
                    log.warn("孤儿附件的 S3 对象删除失败，保留库行待下轮重试 id={} key={}", a.getId(), a.getStorageKey());
                    continue;
                }
                attachmentRepository.delete(a);
                cleaned++;
            } catch (Exception e) {
                log.warn("清理孤儿附件失败 id={} key={}: {}", a.getId(), a.getStorageKey(), e.getMessage());
            }
        }
        return cleaned;
    }
}
