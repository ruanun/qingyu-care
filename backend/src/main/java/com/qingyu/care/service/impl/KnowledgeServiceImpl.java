package com.qingyu.care.service.impl;

import com.qingyu.care.service.LongTaskTenantLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import com.qingyu.care.config.TenantOwnershipGuard;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.dto.KnowledgeBaseRequest;
import com.qingyu.care.dto.KnowledgeBaseUpdateRequest;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.service.FileService;
import com.qingyu.care.service.KnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.dromara.x.file.storage.core.FileInfo;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import com.qingyu.care.config.AsyncConfig;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.qingyu.care.util.AfterCommit;

@Service
@Slf4j
public class KnowledgeServiceImpl implements KnowledgeService {
    
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final com.qingyu.care.service.embedding.KnowledgeVectorWriter vectorWriter;
    private final FileService fileService;
    private final com.qingyu.care.repository.VectorStoreRepository vectorStoreRepository;
    private final com.qingyu.care.repository.QaPairRepository qaPairRepository;
    private final com.qingyu.care.repository.CrawlSiteRepository crawlSiteRepository;

    /**
     * 存储配额闸门。用 setter 注入而非构造注入：本类的构造函数被 10 个单测手工调用，
     * 加一个参数要改 10 处，而配额只在 {@code uploadDocument} 这一条路径上用到。
     * 风格同下方的 {@code setSelf}。⚠️ 没注入时这里会 NPE 而不是静默跳过配额检查——
     * 可见的失败好过悄悄失效。
     */
    private com.qingyu.care.service.QuotaService quotaService;

    @org.springframework.beans.factory.annotation.Autowired
    public void setQuotaService(com.qingyu.care.service.QuotaService quotaService) {
        this.quotaService = quotaService;
    }


    private LongTaskTenantLimiter longTaskLimiter;

    /** setter 注入：构造函数被多条单测手工调用，见 LongTaskTenantLimiter。没注入会 NPE 而不是静默跳过限流。 */
    @Autowired
    public void setLongTaskLimiter(LongTaskTenantLimiter longTaskLimiter) {
        this.longTaskLimiter = longTaskLimiter;
    }

    // 自注入解决 @Async 同类调用不生效问题
    private KnowledgeServiceImpl self;

    public KnowledgeServiceImpl(
            KnowledgeBaseRepository knowledgeBaseRepository,
            DocumentRepository documentRepository,
            com.qingyu.care.service.embedding.KnowledgeVectorWriter vectorWriter,
            FileService fileService,
            com.qingyu.care.repository.VectorStoreRepository vectorStoreRepository,
            com.qingyu.care.repository.QaPairRepository qaPairRepository,
            com.qingyu.care.repository.CrawlSiteRepository crawlSiteRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.vectorWriter = vectorWriter;
        this.fileService = fileService;
        this.vectorStoreRepository = vectorStoreRepository;
        this.qaPairRepository = qaPairRepository;
        this.crawlSiteRepository = crawlSiteRepository;
    }
    
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    public void setSelf(KnowledgeServiceImpl self) {
        this.self = self;
    }
    
    @Value("${qingyu.care.knowledge.chunking.default-chunk-size:1000}")
    private int chunkSize;
    
    @Value("${qingyu.care.knowledge.chunking.default-overlap:200}")
    private int chunkOverlap;

    /** 单个知识库文件上限（字节），默认 30MB；在读入任何字节之前就拦。 */
    @Value("${qingyu.care.knowledge.max-upload-bytes:31457280}")
    private long maxUploadBytes;

    /** 单个文件抽取文本的字符上限，超出截断（默认 200 万字符）。 */
    @Value("${qingyu.care.knowledge.max-extracted-chars:2000000}")
    private int maxExtractedChars = 2_000_000;

    /** 单个文档最多切多少块（默认 2000），封顶 embedding 调用次数。 */
    @Value("${qingyu.care.knowledge.max-chunks-per-document:2000}")
    private int maxChunksPerDocument = 2000;

    /** 与 {@link #detectDocumentType} 的分支一一对应；不在表里的扩展名一律拒绝，不再默认当 TEXT 摄取。 */
    private static final Set<String> UPLOAD_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "html", "htm", "txt", "md",
            "jpg", "jpeg", "png", "gif");

    private static final int MAX_FILENAME_LENGTH = 255;

    /** 纯函数、无状态，不必进容器 */
    private final TikaTextExtractor textExtractor = new TikaTextExtractor();

    @Override
    @Transactional
    public KnowledgeBase createKnowledgeBase(KnowledgeBaseRequest request) {
        KnowledgeBase kb = KnowledgeBase.builder()
                .name(request.getName())
                .description(request.getDescription())
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .build();
        return knowledgeBaseRepository.save(kb);
    }

    /**
     * 按当前应用收窄的知识库列表。TenantDataAspect 对 PLATFORM_ADMIN 整体不启用 @Filter，
     * 裸 findAll 会让平台管理员跨应用读全平台知识库；显式按 appId 收窄，无 appId（未选应用）返空。
     * 注意：管理列表须包含已禁用知识库（禁用是可逆操作，禁用后仍要能在列表里看到并重新启用），
     * 因此用不带 enabled 过滤的 {@link KnowledgeBaseRepository#findByAppId(Long)}；
     * {@code findByAppIdAndEnabledTrue} 是 RAG 检索范围专用，语义不同，不可混用。
     */
    @Override
    public List<KnowledgeBase> getAllKnowledgeBases() {
        Long appId = com.qingyu.care.config.TenantContext.getAppId();
        return appId == null ? List.of() : knowledgeBaseRepository.findByAppId(appId);
    }

    @Override
    public KnowledgeBase getKnowledgeBase(Long id) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Knowledge base not found: " + id));
        // findById 不受 @Filter 保护，显式校验归属，不匹配按「不存在」同语义处理防枚举探测。
        // ⚠️ 用写级守卫：知识库没有「平台默认」这一共享语义（无播种逻辑），NULL 租户的行只可能是
        // 平台管理员无上下文时建的——读级守卫会对其无条件放行，等于任意租户都能往里插文档。
        if (!TenantOwnershipGuard.mutableInCurrentContext(kb.getTenantId(), kb.getAppId())) {
            throw new RuntimeException("Knowledge base not found: " + id);
        }
        return kb;
    }

    @Override
    @Transactional
    public KnowledgeBase updateKnowledgeBase(Long id, KnowledgeBaseUpdateRequest request) {
        KnowledgeBase kb = getKnowledgeBase(id);
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());
        // 刻意不碰 enabled：启用状态由 toggleKnowledgeBase / setKnowledgeBaseEnabled 两个
        // 专用端点负责。此前这里有一段 if (request.getEnabled() != null)，而请求 DTO 的
        // enabled 写了 `= true` 初始化器使判空恒真——改一次名字就把停用的库重新启用了。
        return knowledgeBaseRepository.save(kb);
    }

    /**
     * 删除知识库。<b>库里还有内容时拒绝删除</b>，并在文案里说清各类内容各有多少。
     *
     * <p>此前这里只做 {@code delete(kb)}：{@code document}/{@code qa_pair}/{@code crawl_site}
     * 三张表都有指向 {@code knowledge_base} 的外键，有内容时数据库直接抛
     * {@code DataIntegrityViolationException}，用户拿到的是一个不可读的 500。
     *
     * <p>⚠️ 这里刻意<b>不做级联删除</b>：{@code Document.knowledgeBase} 是
     * {@code nullable = false}，文档没法像 {@code CrawlSite} 被删时那样「置 null 退化成
     * 普通文档」，所以级联就意味着连文档、向量、QA 一起不可恢复地删掉。删知识库本就是
     * 低频且破坏性的操作（该端点前端未接线，只能从 Swagger 或直连 API 触发），
     * 让它在有内容时明确拒绝、把清空的决定留给用户，比替用户做主安全。
     * 真需要「一键连内容删光」时再加显式的 force 参数，仿 {@code deleteSite(deleteDocuments)}。
     */
    @Override
    @Transactional
    public void deleteKnowledgeBase(Long id) {
        // 经 getKnowledgeBase 显式归属校验后再删——deleteById 直连主键同样绕 @Filter
        KnowledgeBase kb = getKnowledgeBase(id);

        long documents = documentRepository.countByKnowledgeBaseId(id);
        long qaPairs = qaPairRepository.countByKnowledgeBaseId(id);
        long crawlSites = crawlSiteRepository.countByKnowledgeBaseId(id);
        if (documents + qaPairs + crawlSites > 0) {
            StringBuilder detail = new StringBuilder();
            if (documents > 0) {
                detail.append(documents).append(" 篇文档");
            }
            if (qaPairs > 0) {
                detail.append(detail.isEmpty() ? "" : "、").append(qaPairs).append(" 条问答对");
            }
            if (crawlSites > 0) {
                detail.append(detail.isEmpty() ? "" : "、").append(crawlSites).append(" 个爬取站点");
            }
            throw new BusinessException("知识库「" + kb.getName() + "」下仍有 " + detail
                    + "，请先清空后再删除");
        }

        knowledgeBaseRepository.delete(kb);
    }

    @Override
    @Transactional
    public Document uploadDocument(Long knowledgeBaseId, MultipartFile file) {
        KnowledgeBase kb = getKnowledgeBase(knowledgeBaseId);
        // 安全审查 M8（2026-09-03）：此前零校验——任意扩展名、任意大小（只有 multipart 的 100MB 硬顶）、
        // 超长文件名直接进 S3，INSERT 失败时对象无人回收。校验放在传 S3 之前，且 getSize() 闸门
        // 先于任何读取字节的操作（同 AttachmentValidator 的顺序）。
        validateUpload(file);
        // 租户级存储配额（安全审查 M5 残留部分）：单文件上限之外还要管总量
        quotaService.requireStorageAvailable(
                com.qingyu.care.config.TenantContext.getTenantId(), file.getSize());
        String safeName = truncateFilename(file.getOriginalFilename());

        // 上传文件到 S3 存储
        FileInfo fileInfo = fileService.uploadKnowledgeFile(file);
        log.info("文件已上传到 S3: {}", fileInfo.getUrl());

        // 创建文档记录
        Document doc = Document.builder()
                .knowledgeBase(kb)
                .name(safeName)
                .originalFilename(safeName)
                .documentType(detectDocumentType(safeName))
                .status(DocumentStatus.PENDING)
                .filePath(fileInfo.getUrl())  // 存储 S3 URL
                .storageKey(fileInfo.getFilename())  // 存储文件 key
                .fileSize(file.getSize())
                .build();
        try {
            doc = documentRepository.save(doc);
        } catch (RuntimeException e) {
            // 落库失败则回收刚传上去的对象：否则留下没有库行指向的孤儿，cleanupOrphans 只扫附件表够不到
            fileService.deleteFile(fileInfo.getUrl());
            throw e;
        }

        // 异步处理文档。两件事都不能省：
        //   · 经 self 调用才命中 Spring 代理，@Async 才真正生效（同类直调会被隐式 this. 绕过）；
        //   · 经 AfterCommit 推迟到本事务提交之后再投递——本方法带 @Transactional，
        //     直接投递时异步线程可能早于提交被调度，READ COMMITTED 下 findById 取不到刚建的
        //     文档行，而 processDocument 那边是 orElse(null) 后直接 return，**完全静默**：
        //     文档永远停在 PENDING、不入向量库、无日志无异常（同站点爬取 C-1 那条竞态）。
        final Long docId = doc.getId();
        AfterCommit.run(() -> self.processDocumentAsync(docId));

        return doc;
    }

    @Override
    public List<Document> getDocuments(Long knowledgeBaseId) {
        // 先经 getKnowledgeBase 做归属校验（同 uploadDocument/getDocument 先校验后操作），
        // 越权 kbId 直接抛出，不查询文档，防跨租户枚举知识库下文档列表
        getKnowledgeBase(knowledgeBaseId);
        return documentRepository.findByKnowledgeBaseId(knowledgeBaseId);
    }
    
    @Override
    public Document getDocument(Long documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        requireDocumentOwnership(doc, documentId);
        return doc;
    }

    /**
     * 文档归属校验：findById 不受 @Filter 保护，加载后显式补校验，不匹配按「不存在」同语义处理防探测。
     * 供 getDocument / deleteDocument / reprocessDocument / getDocumentStatus 复用。
     */
    private void requireDocumentOwnership(Document doc, Long documentId) {
        // ⚠️ 必须是写级守卫：本方法被 getDocument / deleteDocument / reprocessDocument /
        // getDocumentStatus 四处共用，读级守卫对 NULL 租户无条件放行 = 一处豁免同时放开读、删、写。
        // 2026-09-02 实测：另一租户的管理员可读到平台私有文档正文、可将其真的删除、还能往平台
        // 私有知识库里插文档。文档没有「平台默认」语义，NULL 只可能是平台管理员建档的既有行为。
        if (!TenantOwnershipGuard.mutableInCurrentContext(doc.getTenantId(), doc.getAppId())) {
            throw new RuntimeException("Document not found: " + documentId);
        }
    }

    @Override
    @Transactional
    public void deleteDocument(Long documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        requireDocumentOwnership(doc, documentId);

        // 删除向量存储中的数据（根据 metadata 中的 documentId）
        try {
            int deletedCount = vectorStoreRepository.deleteByDocumentId(documentId.toString());
            log.info("Deleted {} vectors for document: {}", deletedCount, documentId);
        } catch (Exception e) {
            log.warn("Failed to delete vectors for document {}: {}", documentId, e.getMessage());
        }
        
        // 删除 S3 文件
        try {
            fileService.deleteFile(doc.getFilePath());
        } catch (Exception e) {
            log.warn("Failed to delete S3 file: {}", e.getMessage());
        }
        
        documentRepository.delete(doc);
    }

    @Override
    public void reprocessDocument(Long documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        requireDocumentOwnership(doc, documentId);
        doc.setStatus(DocumentStatus.PENDING);
        documentRepository.save(doc);
        // 必须走 self：同类直调不经代理，@Async 不生效（重处理会占住请求线程）；
        // 按租户限并发：长任务池全平台共享（2026-09-30 安全审查 M6）
        longTaskLimiter.acquire().launch(() -> self.processDocumentAsync(documentId));
    }

    @Async(AsyncConfig.LONG_TASK_EXECUTOR)
    public java.util.concurrent.CompletableFuture<Void> processDocumentAsync(Long documentId) {
        // 必须走 self：同类直调不经代理，processDocument 的 @Transactional 会失效，
        // 其首行 deleteByDocumentId 是 @Modifying 原生删除，无环绕事务直接抛
        // TransactionRequiredException，整条摄取链路静默失败在 @Async 线程里。
        self.processDocument(documentId);
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    
    @Override
    @Transactional
    public void processDocument(Long documentId) {
        // 互斥：下面的「先删旧向量、再插新向量」是本项目唯一会产出重复向量的临界区，而本方法
        // 有三个入口——网页同步、切换向量模型的迁移任务(EmbeddingMigrationService，经 reembedDocumentFor)、手动重新处理(reprocessDocument)。
        // 锁必须抢在这里而不是某个调用方：只锁住一条时，另外两条照样能并发进入。典型事故是
        // 换 embedding 模型后跑全量重嵌，批任务正处理某篇网页，调度器同一分钟命中同一篇且
        // 正文已变——两个事务各删各插，vector_store 落 2N 行而 chunk_count 记 N，检索时同一
        // 段落重复占 topK，全程无异常无日志（详见 tryLockDocumentProcessing 的注释）。
        //
        // 抢不到就跳过本轮：持锁方做的是同一件事（同一篇文档、同一个 embedding 模型），
        // 让它做完即可，重复做只会再删再插一遍。跳过对三个入口都安全——
        //   · 网页同步：外层 syncDocument 已持同一把锁，同事务可重入，永远抢得到；
        //   · 文件上传后的首次处理：文档 id 刚生成，不可能有别的事务持有它的锁；
        //   · 重嵌批任务：会把这篇按"已完成"计数（它回读 status 判成败，跳过时状态不变），
        //     但持锁方那一轮同样用当前 embedding 模型重嵌，结果与本轮等价，不会留下漏打标的向量。
        if (!documentRepository.tryLockDocumentProcessing(documentId)) {
            log.info("文档处理跳过：同一文档已有处理在进行 id={}", documentId);
            return;
        }

        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) return;

        // 幂等：重嵌/重处理前先删旧向量，避免重复（初次上传时删 0 行无害；供重建向量索引批任务复用）
        vectorStoreRepository.deleteByDocumentId(documentId.toString());

        try {
            doc.setStatus(DocumentStatus.PROCESSING);
            documentRepository.save(doc);

            ExtractedChunks extracted = extractChunks(doc);
            List<org.springframework.ai.document.Document> documents = extracted.documents();
            List<org.springframework.ai.document.Document> chunks = extracted.chunks();

            // 存入向量库：按应用当前的写入目标（切换向量模型进行中时双写，见 EmbeddingModelResolver.writeTargets）
            vectorWriter.write(extracted.appId(), chunks);
            
            // 更新文档状态
            doc.setStatus(DocumentStatus.COMPLETED);
            doc.setChunkCount(chunks.size());
            if (doc.getDocumentType() != DocumentType.URL) {
                // 网页文档的正文本就是 documents 唯一元素的原样内容，重建只会白加一个前导换行，
                // 不覆盖，保持 doc.content 与抓取到的正文逐字一致
                doc.setContent(documents.stream()
                        .map(org.springframework.ai.document.Document::getText)
                        .reduce("", (a, b) -> a + "\n" + b));
            }
            documentRepository.save(doc);
            
            log.info("Document {} processed successfully, {} chunks created", documentId, chunks.size());
            
        } catch (Exception e) {
            log.error("Failed to process document {}: {}", documentId, e.getMessage(), e);
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(e.getMessage());
            documentRepository.save(doc);
        }
    }
    
    /**
     * 只给一个目标向量模型补一份向量（切换向量模型的影子重建用）：先删该文档在目标标签下的旧向量，再写。
     *
     * <p>与 {@link #processDocument} 的区别是<b>不碰文档状态、不碰当前模型的向量</b>——迁移期间文档保持
     * COMPLETED、检索照常读当前模型那份。持同一把文档咨询锁，与重新处理 / 网页同步互斥。
     *
     * @return false 表示被锁挡下（别的处理正在进行），调用方稍后由补漏扫描兜底
     */
    @Override
    @Transactional
    public boolean reembedDocumentFor(Long documentId, com.qingyu.care.entity.AiModelConfig target) throws java.io.IOException {
        if (!documentRepository.tryLockDocumentProcessing(documentId)) {
            log.info("迁移重嵌跳过：同一文档已有处理在进行 id={}", documentId);
            return false;
        }
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null || doc.getStatus() != DocumentStatus.COMPLETED) {
            // 删了，或正在被重新处理（那一轮会按写入目标双写，不用这里管）
            return true;
        }
        ExtractedChunks extracted = extractChunks(doc);
        vectorStoreRepository.deleteByDocumentIdAndTag(documentId.toString(),
                com.qingyu.care.service.embedding.EmbeddingTag.of(target));
        vectorWriter.writeFor(target, extracted.chunks());
        return true;
    }

    /** 抽取正文 + 切分 + 写好除模型标签外的全部 metadata。处理与迁移重嵌共用，保证两边切出来的块一致。 */
    private record ExtractedChunks(Long appId,
                                   List<org.springframework.ai.document.Document> documents,
                                   List<org.springframework.ai.document.Document> chunks) {
    }

    private ExtractedChunks extractChunks(Document doc) throws java.io.IOException {
        Long documentId = doc.getId();
        List<org.springframework.ai.document.Document> documents;
        if (doc.getDocumentType() == DocumentType.URL) {
            // 网页文档没有 S3 文件（filePath 恒为 null），正文已由 WebPageSyncService 抓取
            // 存入 doc.content；跳过下载与 Tika 解析，直接把已有正文包一份 Document 送入切分
            documents = List.of(org.springframework.ai.document.Document.builder()
                    .text(doc.getContent() != null ? doc.getContent() : "")
                    .build());
        } else {
            // 从 S3 流式读取并用 Tika 抽正文，带字符上限（安全审查 M8）：不再整个文件读成 byte[]，
            // 也不再无上限抽文本——抽出多少就切多少块、嵌多少次，费用与堆内存都要封顶
            TikaTextExtractor.ExtractedText extracted;
            try (java.io.InputStream in = fileService.openStream(doc.getFilePath())) {
                extracted = textExtractor.extract(in, maxExtractedChars);
            }
            if (extracted.truncated()) {
                log.warn("文档正文超过 {} 字符已截断，只摄取前半段 id={} name={}",
                        maxExtractedChars, documentId, doc.getName());
            }
            documents = List.of(org.springframework.ai.document.Document.builder()
                    .text(extracted.text())
                    .build());
        }

        // 切分文档（1.x GA 起 5 参构造被移除，改用 builder，punctuationMarks 走默认）
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(chunkOverlap)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(maxChunksPerDocument)   // 单文档 chunk 封顶（安全审查 M8）
                .withKeepSeparator(true)
                .build();
        List<org.springframework.ai.document.Document> chunks = splitter.split(documents);
        // ⚠️ TokenTextSplitter 到达 maxNumChunks 后会把剩余全部文本追加成最后一块（一块可能极大），
        // 所以上限必须在这里再截一次，否则封顶形同虚设
        if (chunks.size() > maxChunksPerDocument) {
            log.warn("文档切块数 {} 超过上限 {}，丢弃超出部分 id={} name={}",
                    chunks.size(), maxChunksPerDocument, documentId, doc.getName());
            chunks = new java.util.ArrayList<>(chunks.subList(0, maxChunksPerDocument));
        }
        
        // 添加元数据（包含租户隔离信息）——
        // 租户/应用取自 document 行自身（@PrePersist 在请求线程已回填）：
        // processDocument 跑在 @Async 线程，无 TenantContext，直接读上下文会写出 appId 空串（历史债根因）
        Long tenantId = doc.getTenantId() != null ? doc.getTenantId() : com.qingyu.care.config.TenantContext.getTenantId();
        Long appId = doc.getAppId() != null ? doc.getAppId() : com.qingyu.care.config.TenantContext.getAppId();
        for (org.springframework.ai.document.Document chunk : chunks) {
            chunk.getMetadata().putAll(Map.of(
                    "documentId", documentId.toString(),
                    "knowledgeBaseId", doc.getKnowledgeBase().getId().toString(),
                    "tenantId", tenantId != null ? tenantId.toString() : "",
                    "appId", appId != null ? appId.toString() : "",
                    "source", doc.getName(),
                    // 检索条件为等值判断，摄取必写全键（缺键行会被静默筛掉）；
                    // docEnabled 取文档实际状态（硬编码 true 会让重新处理已禁用文档时静默重新启用）
                    "visibility", doc.getVisibility() != null ? doc.getVisibility().name() : "PUBLIC",
                    "docEnabled", doc.getEnabled() != null ? doc.getEnabled().toString() : "true"
                    // embeddingModel 标签由 KnowledgeVectorWriter 按写入目标逐份打上，这里不写
            ));
        }
        return new ExtractedChunks(appId, documents, chunks);
    }

    /** 大小（先于任何读字节的操作）→ 非空 → 扩展名白名单。 */
    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("文件为空");
        }
        if (file.getSize() > maxUploadBytes) {
            throw new BusinessException("文件大小超过上限（" + (maxUploadBytes / 1024 / 1024) + "MB）");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!UPLOAD_EXTENSIONS.contains(ext)) {
            throw new BusinessException("不支持的文件类型：" + (ext.isEmpty() ? "（无扩展名）" : ext));
        }
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** name / original_filename 列都是 varchar(255)：超长原名保留扩展名截断，不再撞列宽变 500。 */
    private static String truncateFilename(String filename) {
        if (filename == null) {
            return "unnamed";
        }
        if (filename.length() <= MAX_FILENAME_LENGTH) {
            return filename;
        }
        String ext = extensionOf(filename);
        String suffix = ext.isEmpty() ? "" : "." + ext;
        return filename.substring(0, MAX_FILENAME_LENGTH - suffix.length()) + suffix;
    }

    private DocumentType detectDocumentType(String filename) {
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "pdf" -> DocumentType.PDF;
            case "doc", "docx" -> DocumentType.WORD;
            case "xls", "xlsx" -> DocumentType.EXCEL;
            case "ppt", "pptx" -> DocumentType.PPT;
            case "html", "htm" -> DocumentType.HTML;
            case "txt" -> DocumentType.TEXT;
            case "md" -> DocumentType.MARKDOWN;
            case "jpg", "jpeg", "png", "gif" -> DocumentType.IMAGE;
            default -> DocumentType.TEXT;
        };
    }
    
    // ===== 新增方法实现 =====
    
    @Override
    public com.qingyu.care.dto.DocumentStatusDTO getDocumentStatus(Long documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("文档不存在: " + documentId));
        requireDocumentOwnership(doc, documentId);
        return convertToStatusDTO(doc);
    }
    
    @Override
    public List<com.qingyu.care.dto.DocumentStatusDTO> getDocumentsStatus(List<Long> documentIds) {
        // findAllById 不受 @Filter 保护，按当前上下文归属过滤结果，剔除越权文档（不抛异常，
        // 因入参是批量 id，个别越权 id 不应影响批内其余合法查询）
        return documentRepository.findAllById(documentIds).stream()
                .filter(doc -> TenantOwnershipGuard.mutableInCurrentContext(doc.getTenantId(), doc.getAppId()))
                .map(this::convertToStatusDTO)
                .toList();
    }
    
    @Override
    @Transactional
    public KnowledgeBase toggleKnowledgeBase(Long id) {
        KnowledgeBase kb = getKnowledgeBase(id);
        kb.setEnabled(!kb.getEnabled());
        return knowledgeBaseRepository.save(kb);
    }
    
    @Override
    @Transactional
    public KnowledgeBase setKnowledgeBaseEnabled(Long id, boolean enabled) {
        KnowledgeBase kb = getKnowledgeBase(id);
        kb.setEnabled(enabled);
        return knowledgeBaseRepository.save(kb);
    }

    @Override
    @Transactional
    public Document updateDocumentVisibility(Long documentId, DocumentVisibility visibility) {
        Document doc = getDocument(documentId);
        doc.setVisibility(visibility);
        Document saved = documentRepository.save(doc);
        // 与文档行同事务同步 chunk 标记，保证不出现行与 metadata 不一致的静默状态
        vectorStoreRepository.updateVisibilityByDocumentId(documentId.toString(), visibility.name());
        return saved;
    }

    @Override
    @Transactional
    public Document setDocumentEnabled(Long documentId, boolean enabled) {
        Document doc = getDocument(documentId);
        doc.setEnabled(enabled);
        Document saved = documentRepository.save(doc);
        vectorStoreRepository.updateDocEnabledByDocumentId(documentId.toString(), String.valueOf(enabled));
        return saved;
    }

    /**
     * 转换为轻量级状态 DTO
     */
    private com.qingyu.care.dto.DocumentStatusDTO convertToStatusDTO(Document doc) {
        int progress = calculateProgress(doc.getStatus(), doc.getChunkCount());
        return com.qingyu.care.dto.DocumentStatusDTO.builder()
                .id(doc.getId())
                .name(doc.getName())
                .status(doc.getStatus())
                .chunkCount(doc.getChunkCount())
                .errorMessage(doc.getErrorMessage())
                .progress(progress)
                .build();
    }
    
    /**
     * 计算处理进度百分比
     */
    private int calculateProgress(DocumentStatus status, Integer chunkCount) {
        return switch (status) {
            case PENDING -> 0;
            case PROCESSING -> 50;  // 处理中显示 50%
            case COMPLETED -> 100;
            case FAILED -> 0;
            case DISABLED -> 100;
        };
    }
}

