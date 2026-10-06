package com.qingyu.care.service;

import com.qingyu.care.dto.DocumentStatusDTO;
import com.qingyu.care.dto.KnowledgeBaseRequest;
import com.qingyu.care.dto.KnowledgeBaseUpdateRequest;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库服务接口
 */
public interface KnowledgeService {
    
    /**
     * 创建知识库
     */
    KnowledgeBase createKnowledgeBase(KnowledgeBaseRequest request);
    
    /**
     * 获取所有知识库
     */
    List<KnowledgeBase> getAllKnowledgeBases();
    
    /**
     * 获取知识库详情
     */
    KnowledgeBase getKnowledgeBase(Long id);
    
    /**
     * 更新知识库
     */
    KnowledgeBase updateKnowledgeBase(Long id, KnowledgeBaseUpdateRequest request);
    
    /**
     * 删除知识库
     */
    void deleteKnowledgeBase(Long id);
    
    /**
     * 上传文档到知识库
     */
    Document uploadDocument(Long knowledgeBaseId, MultipartFile file);
    
    /**
     * 获取知识库下的所有文档
     */
    List<Document> getDocuments(Long knowledgeBaseId);
    
    /**
     * 删除文档
     */
    void deleteDocument(Long documentId);
    
    /**
     * 获取单个文档详情
     */
    Document getDocument(Long documentId);
    
    /**
     * 重新处理文档
     */
    void reprocessDocument(Long documentId);

    /**
     * 处理文档（解析+切分+嵌入）：起始先删旧向量，幂等，供重建向量索引批任务复用。
     */
    void processDocument(Long documentId);

    /**
     * 切换向量模型时，给目标模型补一份该文档的向量（不改文档状态、不动当前模型的向量）。
     *
     * @return false 表示文档正被别的处理占用、本轮跳过
     */
    boolean reembedDocumentFor(Long documentId, com.qingyu.care.entity.AiModelConfig target) throws java.io.IOException;

    // ===== 新增方法 =====
    
    /**
     * 获取文档处理状态（轻量级）
     */
    DocumentStatusDTO getDocumentStatus(Long documentId);
    
    /**
     * 批量获取文档处理状态
     */
    List<DocumentStatusDTO> getDocumentsStatus(List<Long> documentIds);
    
    /**
     * 切换知识库启用/禁用状态
     */
    KnowledgeBase toggleKnowledgeBase(Long id);
    
    /**
     * 设置知识库启用状态
     */
    KnowledgeBase setKnowledgeBaseEnabled(Long id, boolean enabled);

    /**
     * 更新文档可见性（同步 chunk metadata）
     */
    Document updateDocumentVisibility(Long documentId, com.qingyu.care.enums.DocumentVisibility visibility);

    /**
     * 设置文档启用状态（同步 chunk metadata；禁用不删 chunk）
     */
    Document setDocumentEnabled(Long documentId, boolean enabled);
}

