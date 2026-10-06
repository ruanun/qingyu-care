package com.qingyu.care.service;

import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentStatus;
import com.qingyu.care.enums.DocumentType;
import com.qingyu.care.repository.CrawlSiteRepository;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.QaPairRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import com.qingyu.care.service.impl.KnowledgeServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.qingyu.care.service.embedding.KnowledgeVectorWriter;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 摄取链路的两道上限（安全审查 M8 第二段，2026-09-03）：抽取文本字符数、单文档 chunk 数。
 * 没有它们时，一个超大文件抽出多少文本就切多少块、嵌多少次，embedding 费用与堆内存都没有封顶。
 * 文件改为从 S3 流式读取（{@code openStream}），不再整个读成 {@code byte[]}。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeIngestLimitsTest {

    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock DocumentRepository documentRepository;
    @Mock KnowledgeVectorWriter vectorWriter;
    @Mock FileService fileService;
    @Mock VectorStoreRepository vectorStoreRepository;
    @Mock QaPairRepository qaPairRepository;
    @Mock CrawlSiteRepository crawlSiteRepository;

    private KnowledgeServiceImpl service(int maxExtractedChars, int maxChunks) {
        KnowledgeServiceImpl service = new KnowledgeServiceImpl(knowledgeBaseRepository, documentRepository,
                vectorWriter, fileService, vectorStoreRepository,
                qaPairRepository, crawlSiteRepository);
        ReflectionTestUtils.setField(service, "chunkSize", 50);
        ReflectionTestUtils.setField(service, "chunkOverlap", 10);
        ReflectionTestUtils.setField(service, "maxExtractedChars", maxExtractedChars);
        ReflectionTestUtils.setField(service, "maxChunksPerDocument", maxChunks);
        return service;
    }

    private Document stubFileDocument(String content) {
        Document doc = Document.builder().id(5L).name("说明书.txt")
                .knowledgeBase(KnowledgeBase.builder().id(10L).build())
                .documentType(DocumentType.TEXT).filePath("kb/说明书.txt").enabled(true).build();
        doc.setTenantId(1L);
        doc.setAppId(2L);
        when(documentRepository.tryLockDocumentProcessing(5L)).thenReturn(true);
        when(documentRepository.findById(5L)).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fileService.openStream("kb/说明书.txt"))
                .thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return doc;
    }

    @SuppressWarnings("unchecked")
    private List<org.springframework.ai.document.Document> addedChunks() {
        ArgumentCaptor<List<org.springframework.ai.document.Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorWriter).write(any(), captor.capture());
        return captor.getValue();
    }

    /** 变异实验：把抽取上限改回无限（-1），本条转红。 */
    @Test
    void 抽取文本超过上限时截断后仍完成摄取() {
        Document doc = stubFileDocument("轻语客服知识内容，用于验证抽取上限。".repeat(500));   // 约 9000 字符

        service(600, 10_000).processDocument(5L);

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        int total = addedChunks().stream().mapToInt(c -> c.getText().length()).sum();
        // 分词器解码边界可能多出个别字符，留一点余量；关键是远小于原文的 ~9000
        assertThat(total).as("落进向量库的总字符数不该明显超过抽取上限").isLessThanOrEqualTo(620);
        verify(fileService, never()).downloadFile(any());
    }

    /** 变异实验：把 splitter 的 maxNumChunks 改回写死的 10000，本条转红。 */
    @Test
    void 单文档chunk数封顶() {
        Document doc = stubFileDocument("轻语客服知识内容，用于验证分块上限。".repeat(200));

        service(1_000_000, 2).processDocument(5L);

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(addedChunks()).hasSizeLessThanOrEqualTo(2);
    }
}
