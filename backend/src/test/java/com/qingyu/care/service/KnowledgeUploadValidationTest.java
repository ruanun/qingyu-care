package com.qingyu.care.service;

import com.qingyu.care.entity.Document;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.exception.BusinessException;
import com.qingyu.care.repository.CrawlSiteRepository;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.QaPairRepository;
import com.qingyu.care.repository.VectorStoreRepository;
import com.qingyu.care.service.impl.KnowledgeServiceImpl;
import org.dromara.x.file.storage.core.FileInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库文档上传的前置校验与失败回收（安全审查 M8，2026-09-03）。
 *
 * <p>此前 {@code uploadDocument} 没有任何自有校验：任意扩展名、任意大小（只有 multipart 的
 * 100MB 硬顶）、超长文件名直接进 S3，随后 INSERT 失败时对象无人回收——租户成员反复上传就能
 * 填满对象存储且无库行可追踪。对比聊天附件那条路径（{@code AttachmentValidator} 四道校验 +
 * 失败回滚），知识库这条一道都没有。
 *
 * @author ruan
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeUploadValidationTest {

    @Mock KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock DocumentRepository documentRepository;
    @Mock VectorStore vectorStore;
    @Mock FileService fileService;
    @Mock VectorStoreRepository vectorStoreRepository;
    @Mock EmbeddingModelProvider embeddingModelProvider;
    @Mock QaPairRepository qaPairRepository;
    @Mock CrawlSiteRepository crawlSiteRepository;

    private KnowledgeServiceImpl service() {
        KnowledgeServiceImpl service = new KnowledgeServiceImpl(knowledgeBaseRepository, documentRepository,
                vectorStore, fileService, vectorStoreRepository, embeddingModelProvider,
                qaPairRepository, crawlSiteRepository);
        ReflectionTestUtils.setField(service, "chunkSize", 800);
        ReflectionTestUtils.setField(service, "chunkOverlap", 100);
        ReflectionTestUtils.setField(service, "maxUploadBytes", 1024L);   // 单测用 1KB 上限，免得造大文件
        // 上传成功后经 self 投递异步摄取；单测没有 Spring 代理，给一个桩免 NPE
        ReflectionTestUtils.setField(service, "self", org.mockito.Mockito.mock(KnowledgeServiceImpl.class));
        return service;
    }

    private void stubKb() {
        when(knowledgeBaseRepository.findById(10L)).thenReturn(Optional.of(KnowledgeBase.builder().id(10L).build()));
    }

    private static MultipartFile file(String name, int size) {
        return new MockMultipartFile("file", name, "application/octet-stream", new byte[size]);
    }

    @Test
    void 不支持的扩展名被拒绝且不上传() {
        stubKb();

        assertThatThrownBy(() -> service().uploadDocument(10L, file("payload.exe", 10)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持");
        verify(fileService, never()).uploadKnowledgeFile(any());
    }

    @Test
    void 空文件被拒绝() {
        stubKb();

        assertThatThrownBy(() -> service().uploadDocument(10L, file("empty.pdf", 0)))
                .isInstanceOf(BusinessException.class);
        verify(fileService, never()).uploadKnowledgeFile(any());
    }

    /** 变异实验：把 getSize() 闸门删掉，本条转红——上限只剩 multipart 的 100MB。 */
    @Test
    void 超过上限的文件被拒绝且不上传() {
        stubKb();

        assertThatThrownBy(() -> service().uploadDocument(10L, file("big.pdf", 1025)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过");
        verify(fileService, never()).uploadKnowledgeFile(any());
    }

    /** 变异实验：把 save 外面的 try/catch 删掉，本条转红——对象留在 S3、没有库行指向。 */
    @Test
    void 落库失败时删除已上传的对象() {
        stubKb();
        FileInfo info = new FileInfo();
        info.setUrl("http://s3/qingyu-care/knowledge/documents/2026/x.pdf");
        info.setFilename("x.pdf");
        when(fileService.uploadKnowledgeFile(any())).thenReturn(info);
        when(documentRepository.save(any(Document.class))).thenThrow(new RuntimeException("INSERT 失败"));

        assertThatThrownBy(() -> service().uploadDocument(10L, file("ok.pdf", 10)))
                .isInstanceOf(RuntimeException.class);
        verify(fileService).deleteFile("http://s3/qingyu-care/knowledge/documents/2026/x.pdf");
    }

    /** name 列是 varchar(255)，超长原名此前直接撞列宽 → 500。 */
    @Test
    void 超长文件名截断后落库() {
        stubKb();
        FileInfo info = new FileInfo();
        info.setUrl("http://s3/x");
        info.setFilename("x.pdf");
        when(fileService.uploadKnowledgeFile(any())).thenReturn(info);
        when(documentRepository.save(any(Document.class))).thenAnswer(inv -> {
            Document d = inv.getArgument(0);
            d.setId(1L);
            return d;
        });
        String longName = "a".repeat(300) + ".pdf";

        service().uploadDocument(10L, file(longName, 10));

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertThat(captor.getValue().getName().length()).isLessThanOrEqualTo(255);
        assertThat(captor.getValue().getName()).endsWith(".pdf");
    }
}
