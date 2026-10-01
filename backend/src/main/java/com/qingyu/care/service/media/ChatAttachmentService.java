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

    /**
     * 单个会话最多上传多少附件（含尚未挂到消息上的）。appKey 是公开的、会话可以随便建，没有这层上限时
     * 一个滥用者按限流上限持续灌 20MB 文件就能填满对象存储（安全审查 M5，2026-09-03）。
     */
    @org.springframework.beans.factory.annotation.Value("${qingyu.care.attachment.max-per-session:20}")
    private int maxAttachmentsPerSession = 20;

    /**
     * 每个应用每天访客最多上传多少个附件 / 多少字节；0 = 不限。
     *
     * <p>2026-09-30 安全审查：单会话上限（上面那条）挡不住「不断建新会话」，而访客附件与知识库文档共用租户存储配额——
     * 匿名访客转人工后（此时发消息不调模型、几乎零成本）循环「上传 20MB → 挂到消息上」，挂上消息的附件不算孤儿、
     * 不会被 2 小时清理掉，FREE 套餐 1GB 一分钟就能灌满，之后租户管理员连知识库文档都传不了。访客这一路单独封顶，
     * 坐席上传不受影响。
     */
    @org.springframework.beans.factory.annotation.Value("${qingyu.care.attachment.visitor-daily-max-count-per-app:200}")
    private int visitorDailyMaxCount = 200;

    @org.springframework.beans.factory.annotation.Value("${qingyu.care.attachment.visitor-daily-max-bytes-per-app:1073741824}")
    private long visitorDailyMaxBytes = 1024L * 1024 * 1024;

    /** 孤儿清理单轮批量上限，避免积压很深时一次性把全部孤儿读进持久化上下文再串行做网络删除 */
    private static final int CLEANUP_BATCH_SIZE = 200;

    private final ChatAttachmentRepository attachmentRepository;
    private final ChatSessionRepository sessionRepository;
    private final FileService fileService;
    private final AttachmentValidator validator;
    private final com.qingyu.care.service.QuotaService quotaService;

    /** 访客当日上传额度（按应用、按自然日）。并发上传会有少量超出，是软上限，防的是持续灌存储。 */
    void requireVisitorDailyQuota(long incomingBytes) {
        if (visitorDailyMaxCount <= 0 && visitorDailyMaxBytes <= 0) {
            return;
        }
        Long appId = com.qingyu.care.config.TenantContext.getAppId();
        if (appId == null) {
            return;
        }
        java.util.List<Object[]> rows = attachmentRepository.visitorUsageSince(
                appId, java.time.LocalDate.now().atStartOfDay());
        Object[] row = rows.isEmpty() ? new Object[]{0L, 0L} : rows.get(0);
        long count = ((Number) row[0]).longValue();
        long bytes = ((Number) row[1]).longValue();
        if ((visitorDailyMaxCount > 0 && count >= visitorDailyMaxCount)
                || (visitorDailyMaxBytes > 0 && bytes + incomingBytes > visitorDailyMaxBytes)) {
            throw new BusinessException("今日附件上传已达上限，请明天再试或直接用文字描述");
        }
    }

    public ChatAttachment upload(String sessionId, MultipartFile file, MessageRole uploaderRole) {
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BusinessException("文件不能超过 20MB");
        }
        requireOwnedSession(sessionId);
        long existing = attachmentRepository.countBySessionId(sessionId);
        if (existing >= maxAttachmentsPerSession) {
            throw new BusinessException("该会话的附件数量已达上限（" + maxAttachmentsPerSession + " 个）");
        }
        if (uploaderRole == MessageRole.USER) {
            requireVisitorDailyQuota(file.getSize());
        }
        // 租户级存储配额：单会话上限挡不住「不断建新会话」，总量闸门在这里
        quotaService.requireStorageAvailable(com.qingyu.care.config.TenantContext.getTenantId(), file.getSize());
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
     * sessionId 归属校验：必须指向一个当前上下文能写的会话，越权/不存在与「不存在」
     * 同语义处理（防枚举探测）。
     *
     * <p>⚠️ 空白 sessionId **拒绝**，不是放行（复核推翻了本方法上一版"空白放行"的裁定）：
     * {@code chat_attachment.session_id} 是 {@code NOT NULL}，空白会话上传只能落成
     * {@code ""}——这是一个所有租户/应用共享的命名空间。放行会引出两个后果：
     * <ol>
     *   <li>安全——开放 API 下载端点靠 sessionId 相等作第二重凭证（见
     *       {@code OpenChatController#downloadAttachment}），任何调用方带一个空白
     *       sessionId 就能命中同一 app 下所有"空会话上传"的附件，把该防线开回一半；</li>
     *   <li>功能——{@link #attachToMessage} 要求 sessionId 精确相等，空白附件的
     *       sessionId 永远等不上真实会话的 sessionId，传上去就是一条只能等 24 小时孤儿
     *       清理的死数据，没有任何补救路径能把它安全地过继给某个具体访客的真实会话
     *       （空白命名空间下所有访客的孤儿都长一个样，服务端分不清归属）。</li>
     * </ol>
     * 「先选图再发第一条消息」这个交互仍然成立，但責任在调用方：widget 必须在上传前确保
     * 会话已存在（初始化时建会话，或本地先用 {@code URL.createObjectURL} 预览、发送时
     * 才真正上传），不该由服务端开一个共享命名空间来迁就。
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
            throw new BusinessException("缺少会话标识");
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
        List<Long> distinctIds = distinctCapped(attachmentIds);
        for (Long id : distinctIds) {
            ChatAttachment a = loadOwnedAttachment(id, sessionId);
            if (a.getMessageId() != null) {
                throw new BusinessException("附件已被使用");
            }
            a.setMessageId(messageId);
            attachmentRepository.save(a);
        }
    }

    /**
     * 附件可关联性预校验（不做实际关联、不落库）：供 {@code streamChat} 在开始流式响应前调用，
     * 尽早暴露"会话已过期/附件不属于当前会话"这类**确定性**错误（如 widget 会话过期后清本地
     * 重开、却带着旧 sessionId 下的 attachmentIds 发消息），用户能在流开始前就收到明确报错，
     * 而不是等一整轮流式回复吐完、落库时才悄悄失败（C-1：{@code persistStreamedMessages}
     * 运行在既有的 fail-soft 兜底 catch 之内，附件校验异常会连累整条 AI 消息一起静默丢失）。
     * <p>只做「存在性 + 租户归属 + 会话归属」三项，**不检查是否已被占用**——占用状态在预校验
     * 与真正落库之间的窗口内仍可能变化（如同一批附件被并发重复提交），属于本质上无法靠
     * 预校验消灭的竞态；这类残余失败交给 {@code attachToMessage} 真正执行时兜底为 fail-soft，
     * 不在这里提前拒绝制造误判。
     */
    public void validateAttachable(List<Long> attachmentIds, String sessionId) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        if (sessionId == null) {
            throw new BusinessException("会话标识不能为空");
        }
        for (Long id : distinctCapped(attachmentIds)) {
            loadOwnedAttachment(id, sessionId);
        }
    }

    /** 去重 + 数量上限校验，attachToMessage 与 validateAttachable 共用。 */
    private List<Long> distinctCapped(List<Long> attachmentIds) {
        List<Long> distinctIds = attachmentIds.stream().distinct().toList();
        if (distinctIds.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
            throw new BusinessException("单条消息最多携带 " + MAX_ATTACHMENTS_PER_MESSAGE + " 个附件");
        }
        return distinctIds;
    }

    /**
     * 存在性 + 租户归属 + 会话归属三道校验，{@code attachToMessage}（另加"占用状态"）与
     * {@code validateAttachable} 共用。
     */
    private ChatAttachment loadOwnedAttachment(Long id, String sessionId) {
        ChatAttachment a = attachmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("附件不存在: " + id));
        if (!TenantOwnershipGuard.mutableInCurrentContext(a.getTenantId(), a.getAppId())) {
            throw new ResourceNotFoundException("附件不存在: " + id);
        }
        if (!sessionId.equals(a.getSessionId())) {
            throw new BusinessException("附件不属于当前会话");
        }
        return a;
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
