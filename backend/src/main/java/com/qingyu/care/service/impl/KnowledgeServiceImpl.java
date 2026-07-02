package com.qingyu.care.service.impl;

import com.qingyu.care.dto.KnowledgeBaseRequest;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.service.FileService;
import com.qingyu.care.service.KnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.dromara.x.file.storage.core.FileInfo;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class KnowledgeServiceImpl implements KnowledgeService {
    
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final VectorStore vectorStore;
    private final FileService fileService;
    private final com.qingyu.care.repository.VectorStoreRepository vectorStoreRepository;
    
    // 自注入解决 @Async 同类调用不生效问题
    private KnowledgeServiceImpl self;
    
    public KnowledgeServiceImpl(
            KnowledgeBaseRepository knowledgeBaseRepository,
            DocumentRepository documentRepository,
            VectorStore vectorStore,
            FileService fileService,
            com.qingyu.care.repository.VectorStoreRepository vectorStoreRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.vectorStore = vectorStore;
        this.fileService = fileService;
        this.vectorStoreRepository = vectorStoreRepository;
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

    @Override
    public List<KnowledgeBase> getAllKnowledgeBases() {
        return knowledgeBaseRepository.findAll();
    }

    @Override
    public KnowledgeBase getKnowledgeBase(Long id) {
        return knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Knowledge base not found: " + id));
    }

    @Override
    @Transactional
    public KnowledgeBase updateKnowledgeBase(Long id, KnowledgeBaseRequest request) {
        KnowledgeBase kb = getKnowledgeBase(id);
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());
        if (request.getEnabled() != null) {
            kb.setEnabled(request.getEnabled());
        }
        return knowledgeBaseRepository.save(kb);
    }

    @Override
    @Transactional
    public void deleteKnowledgeBase(Long id) {
        knowledgeBaseRepository.deleteById(id);
    }

    @Override
    @Transactional
    public Document uploadDocument(Long knowledgeBaseId, MultipartFile file) {
        KnowledgeBase kb = getKnowledgeBase(knowledgeBaseId);
        
        // 上传文件到 S3 存储
        FileInfo fileInfo = fileService.uploadKnowledgeFile(file);
        log.info("文件已上传到 S3: {}", fileInfo.getUrl());
        
        // 创建文档记录
        Document doc = Document.builder()
                .knowledgeBase(kb)
                .name(file.getOriginalFilename())
                .originalFilename(file.getOriginalFilename())
                .documentType(detectDocumentType(file.getOriginalFilename()))
                .status(DocumentStatus.PENDING)
                .filePath(fileInfo.getUrl())  // 存储 S3 URL
                .storageKey(fileInfo.getFilename())  // 存储文件 key
                .fileSize(file.getSize())
                .build();
        doc = documentRepository.save(doc);
        
        // 异步处理文档（通过 self 调用确保 @Async 生效）
        self.processDocumentAsync(doc.getId());
        
        return doc;
    }

    @Override
    public List<Document> getDocuments(Long knowledgeBaseId) {
        return documentRepository.findByKnowledgeBaseId(knowledgeBaseId);
    }
    
    @Override
    public Document getDocument(Long documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
    }

    @Override
    @Transactional
    public void deleteDocument(Long documentId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));
        
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
        doc.setStatus(DocumentStatus.PENDING);
        documentRepository.save(doc);
        processDocumentAsync(documentId);
    }
    
    @Async
    public void processDocumentAsync(Long documentId) {
        processDocument(documentId);
    }
    
    @Transactional
    public void processDocument(Long documentId) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) return;
        
        try {
            doc.setStatus(DocumentStatus.PROCESSING);
            documentRepository.save(doc);
            
            // 从 S3 下载文件并使用 Tika 解析
            byte[] fileBytes = fileService.downloadFile(doc.getFilePath());
            TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(fileBytes));
            List<org.springframework.ai.document.Document> documents = reader.get();
            
            // 切分文档
            TokenTextSplitter splitter = new TokenTextSplitter(chunkSize, chunkOverlap, 5, 10000, true);
            List<org.springframework.ai.document.Document> chunks = splitter.split(documents);
            
            // 添加元数据（包含租户隔离信息）
            Long tenantId = com.qingyu.care.config.TenantContext.getTenantId();
            Long appId = com.qingyu.care.config.TenantContext.getAppId();
            for (org.springframework.ai.document.Document chunk : chunks) {
                chunk.getMetadata().putAll(Map.of(
                        "documentId", documentId.toString(),
                        "knowledgeBaseId", doc.getKnowledgeBase().getId().toString(),
                        "tenantId", tenantId != null ? tenantId.toString() : "",
                        "appId", appId != null ? appId.toString() : "",
                        "source", doc.getName()
                ));
            }
            
            // 存入向量库
            vectorStore.add(chunks);
            
            // 更新文档状态
            doc.setStatus(DocumentStatus.COMPLETED);
            doc.setChunkCount(chunks.size());
            doc.setContent(documents.stream()
                    .map(org.springframework.ai.document.Document::getContent)
                    .reduce("", (a, b) -> a + "\n" + b));
            documentRepository.save(doc);
            
            log.info("Document {} processed successfully, {} chunks created", documentId, chunks.size());
            
        } catch (Exception e) {
            log.error("Failed to process document {}: {}", documentId, e.getMessage(), e);
            doc.setStatus(DocumentStatus.FAILED);
            doc.setErrorMessage(e.getMessage());
            documentRepository.save(doc);
        }
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
        return convertToStatusDTO(doc);
    }
    
    @Override
    public List<com.qingyu.care.dto.DocumentStatusDTO> getDocumentsStatus(List<Long> documentIds) {
        return documentRepository.findAllById(documentIds).stream()
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

