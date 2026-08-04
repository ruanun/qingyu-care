package com.qingyu.care.service;

import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import com.qingyu.care.service.impl.KnowledgeServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * processDocument 对 URL 类型文档的分支：跳过 S3 下载 + Tika 解析，直接用 doc.content 切分嵌入。
 *
 * <p>这条分支此前完全缺失是网站知识库特性上线后的一个 Critical 缺陷：网页文档 filePath 恒为
 * null，走到既有代码必然 downloadFile 抛异常 → 被 processDocument 自身 catch 吞掉 → 状态
 * FAILED；而调用方 {@code WebPageSyncService.syncDocument} 是 fail-soft 且原先在失败之前就已
 * 落库 contentHash，导致网页正文从未真正进过向量库，但现象完全静默（详见
 * {@link com.qingyu.care.service.web.WebPageSyncServiceTest}）。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeServiceUrlDocumentTest {

    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock DocumentRepository documentRepository;
    @Mock VectorStore vectorStore;
    @Mock FileService fileService;
    @Mock VectorStoreRepository vectorStoreRepository;
    @Mock EmbeddingModelProvider embeddingModelProvider;

    private KnowledgeServiceImpl service() {
        KnowledgeServiceImpl service = new KnowledgeServiceImpl(knowledgeBaseRepository, documentRepository,
                vectorStore, fileService, vectorStoreRepository, embeddingModelProvider);
        ReflectionTestUtils.setField(service, "chunkSize", 800);
        ReflectionTestUtils.setField(service, "chunkOverlap", 100);
        return service;
    }

    @Test
    void URL类型文档不走S3下载_直接用doc内容切分嵌入() {
        KnowledgeBase kb = KnowledgeBase.builder().id(10L).build();
        String marker = "网页正文标记ABC123";
        String pageContent = marker + "，这是一篇网页正文，用于验证 URL 分支直接使用 doc.content 切分嵌入。";
        Document doc = Document.builder()
                .id(6L)
                .name("示例网页")
                .knowledgeBase(kb)
                .documentType(DocumentType.URL)
                .sourceUrl("https://example.com/faq")
                .filePath(null)                 // 网页文档恒为 null，是 S3 下载必炸的根因
                .content(pageContent)
                .build();

        when(documentRepository.findById(6L)).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(embeddingModelProvider.getModel()).thenReturn("qwen3.7-text-embedding");

        service().processDocument(6L);

        // 核心断言：URL 类型绝不能碰 S3 下载
        verify(fileService, never()).downloadFile(any());
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        // 不因切分重建逻辑而被覆盖出多余的前导换行，保持与抓取正文逐字一致
        assertThat(doc.getContent()).isEqualTo(pageContent);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<org.springframework.ai.document.Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        List<org.springframework.ai.document.Document> chunks = captor.getValue();
        assertThat(chunks).isNotEmpty();
        // 切片文本拼回后必须包含原文标记——证明切分输入确实是 doc.getContent()，不是空文本
        String rejoined = chunks.stream()
                .map(org.springframework.ai.document.Document::getText)
                .reduce("", (a, b) -> a + b);
        assertThat(rejoined).contains(marker);
        // embeddingModel 是检索护栏用来排除"跨模型/未打标"向量的关键键（见 vector-reembed 特性），
        // 缺这一个键该文档会被检索静默排除——本次改动最容易漏掉、也最该守住的一条
        for (org.springframework.ai.document.Document chunk : chunks) {
            assertThat(chunk.getMetadata()).containsEntry("documentId", "6");
            assertThat(chunk.getMetadata()).containsEntry("embeddingModel", "qwen3.7-text-embedding");
        }
    }

    @Test
    void 非URL类型文档仍走S3下载与Tika解析_行为不回归() {
        KnowledgeBase kb = KnowledgeBase.builder().id(10L).build();
        Document doc = Document.builder()
                .id(7L)
                .name("说明书.txt")
                .knowledgeBase(kb)
                .documentType(DocumentType.TEXT)
                .filePath("kb/说明书.txt")
                .build();

        when(documentRepository.findById(7L)).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(embeddingModelProvider.getModel()).thenReturn("qwen3.7-text-embedding");
        String content = "轻语客服知识内容，用于验证非 URL 分支未被本次改动波及。".repeat(30);
        when(fileService.downloadFile(any())).thenReturn(content.getBytes(StandardCharsets.UTF_8));

        service().processDocument(7L);

        // 核心断言：非 URL 类型必须仍然走 S3 下载 + Tika（这是本次改动最容易悄悄破坏的既有主路）
        verify(fileService).downloadFile("kb/说明书.txt");
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        verify(vectorStore).add(any());
    }
}
