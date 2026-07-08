package com.qingyu.care.controller;

import com.qingyu.care.annotation.AuditLog;
import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.DocumentStatusDTO;
import com.qingyu.care.dto.KnowledgeBaseRequest;
import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.service.KnowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库控制器
 */
@RestController
@RequestMapping("/v1/knowledge")
@RequiredArgsConstructor
@Tag(name = "Knowledge", description = "知识库管理接口")
public class KnowledgeController {
    
    private final KnowledgeService knowledgeService;
    
    @PostMapping
    @Operation(summary = "创建知识库", description = "创建新的知识库")
    public ApiResponse<KnowledgeBase> createKnowledgeBase(@Valid @RequestBody KnowledgeBaseRequest request) {
        KnowledgeBase kb = knowledgeService.createKnowledgeBase(request);
        return ApiResponse.success("知识库创建成功", kb);
    }
    
    @GetMapping
    @Operation(summary = "获取知识库列表", description = "获取所有知识库")
    public ApiResponse<List<KnowledgeBase>> getAllKnowledgeBases() {
        List<KnowledgeBase> list = knowledgeService.getAllKnowledgeBases();
        return ApiResponse.success(list);
    }
    
    @GetMapping("/{id}")
    @Operation(summary = "获取知识库详情", description = "获取指定知识库的详细信息")
    public ApiResponse<KnowledgeBase> getKnowledgeBase(@PathVariable Long id) {
        KnowledgeBase kb = knowledgeService.getKnowledgeBase(id);
        return ApiResponse.success(kb);
    }
    
    @PutMapping("/{id}")
    @Operation(summary = "更新知识库", description = "更新知识库信息")
    public ApiResponse<KnowledgeBase> updateKnowledgeBase(
            @PathVariable Long id, 
            @Valid @RequestBody KnowledgeBaseRequest request) {
        KnowledgeBase kb = knowledgeService.updateKnowledgeBase(id, request);
        return ApiResponse.success("知识库更新成功", kb);
    }
    
    @DeleteMapping("/{id}")
    @Operation(summary = "删除知识库", description = "删除指定的知识库")
    public ApiResponse<Void> deleteKnowledgeBase(@PathVariable Long id) {
        knowledgeService.deleteKnowledgeBase(id);
        return ApiResponse.success("知识库删除成功", null);
    }
    
    @PutMapping("/{id}/toggle")
    @Operation(summary = "切换知识库状态", description = "启用/禁用知识库")
    public ApiResponse<KnowledgeBase> toggleKnowledgeBase(@PathVariable Long id) {
        KnowledgeBase kb = knowledgeService.toggleKnowledgeBase(id);
        String message = kb.getEnabled() ? "知识库已启用" : "知识库已禁用";
        return ApiResponse.success(message, kb);
    }
    
    @PutMapping("/{id}/enabled")
    @Operation(summary = "设置知识库启用状态", description = "明确设置知识库启用或禁用")
    public ApiResponse<KnowledgeBase> setKnowledgeBaseEnabled(
            @PathVariable Long id,
            @RequestParam boolean enabled) {
        KnowledgeBase kb = knowledgeService.setKnowledgeBaseEnabled(id, enabled);
        return ApiResponse.success(enabled ? "知识库已启用" : "知识库已禁用", kb);
    }
    
    @PostMapping("/{id}/documents")
    @Operation(summary = "上传文档", description = "上传文档到知识库")
    public ApiResponse<Document> uploadDocument(
            @PathVariable Long id, 
            @RequestParam("file") MultipartFile file) {
        Document doc = knowledgeService.uploadDocument(id, file);
        return ApiResponse.success("文档上传成功，正在处理中", doc);
    }
    
    @GetMapping("/{id}/documents")
    @Operation(summary = "获取文档列表", description = "获取知识库下的所有文档")
    public ApiResponse<List<Document>> getDocuments(@PathVariable Long id) {
        List<Document> docs = knowledgeService.getDocuments(id);
        return ApiResponse.success(docs);
    }
    
    @GetMapping("/documents/{documentId}")
    @Operation(summary = "获取文档详情", description = "获取单个文档的详细信息和处理状态")
    public ApiResponse<Document> getDocument(@PathVariable Long documentId) {
        Document doc = knowledgeService.getDocument(documentId);
        return ApiResponse.success(doc);
    }
    
    @PutMapping("/documents/{documentId}/visibility")
    @Operation(summary = "更新文档可见性", description = "PUBLIC 对所有渠道可见；INTERNAL 仅受信调用（API Key/后台）可见")
    @AuditLog(action = "DOC_VISIBILITY_UPDATE", targetType = "Document")
    public ApiResponse<Document> updateDocumentVisibility(
            @PathVariable Long documentId,
            @RequestParam DocumentVisibility visibility) {
        Document doc = knowledgeService.updateDocumentVisibility(documentId, visibility);
        return ApiResponse.success("文档可见性已更新为 " + visibility, doc);
    }

    @PutMapping("/documents/{documentId}/enabled")
    @Operation(summary = "设置文档启用状态", description = "禁用的文档不参与检索；不删向量，重新启用免重嵌入")
    @AuditLog(action = "DOC_ENABLED_UPDATE", targetType = "Document")
    public ApiResponse<Document> setDocumentEnabled(
            @PathVariable Long documentId,
            @RequestParam boolean enabled) {
        Document doc = knowledgeService.setDocumentEnabled(documentId, enabled);
        return ApiResponse.success(enabled ? "文档已启用" : "文档已禁用", doc);
    }

    @GetMapping("/documents/{documentId}/status")
    @Operation(summary = "获取文档处理状态", description = "轻量级端点，仅返回处理状态和进度")
    public ApiResponse<DocumentStatusDTO> getDocumentStatus(@PathVariable Long documentId) {
        DocumentStatusDTO status = knowledgeService.getDocumentStatus(documentId);
        return ApiResponse.success(status);
    }
    
    @PostMapping("/documents/status/batch")
    @Operation(summary = "批量获取文档处理状态", description = "一次查询多个文档的处理状态")
    public ApiResponse<List<DocumentStatusDTO>> getDocumentsStatus(@RequestBody List<Long> documentIds) {
        List<DocumentStatusDTO> statusList = knowledgeService.getDocumentsStatus(documentIds);
        return ApiResponse.success(statusList);
    }
    
    @DeleteMapping("/documents/{documentId}")
    @Operation(summary = "删除文档", description = "删除指定的文档")
    public ApiResponse<Void> deleteDocument(@PathVariable Long documentId) {
        knowledgeService.deleteDocument(documentId);
        return ApiResponse.success("文档删除成功", null);
    }
    
    @PostMapping("/documents/{documentId}/reprocess")
    @Operation(summary = "重新处理文档", description = "重新解析并向量化文档")
    public ApiResponse<Void> reprocessDocument(@PathVariable Long documentId) {
        knowledgeService.reprocessDocument(documentId);
        return ApiResponse.success("文档重新处理已开始", null);
    }
}

