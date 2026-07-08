package com.qingyu.care.service;

import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.KeywordSearchDao;
import com.qingyu.care.service.impl.HybridRetrievalServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HybridRetrievalServiceTest {

    @Mock VectorStore vectorStore;
    @Mock KeywordSearchDao keywordSearchDao;
    @InjectMocks HybridRetrievalServiceImpl service;

    private ChatStrategy strategy(boolean hybrid) {
        ChatStrategy s = new ChatStrategy();
        s.setTopK(10);
        s.setSimilarityThreshold(0.7);
        s.setHybridEnabled(hybrid);
        s.setKeywordTopK(10);
        s.setRrfK(60);
        return s;
    }

    private RetrievalScope scope() {
        return new RetrievalScope(1L, 2L, List.of(10L), com.qingyu.care.enums.DocumentVisibility.INTERNAL);
    }

    private Document vecDoc(String id) {
        return new Document(id, "向量内容" + id, Map.of());
    }

    @Test
    void 两路融合_关键词命中项进入结果() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(vecDoc("a"), vecDoc("b")));
        when(keywordSearchDao.search(any(), any(RetrievalScope.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new KeywordSearchDao.KeywordHit("c", "关键词内容c", Map.of())));

        List<Document> out = service.search("查询", scope(), strategy(true));

        assertThat(out).extracting(Document::getId).contains("a", "b", "c");
    }

    @Test
    void 关键词路异常_降级为纯向量() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(vecDoc("a")));
        when(keywordSearchDao.search(any(), any(RetrievalScope.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException("fts down"));

        List<Document> out = service.search("查询", scope(), strategy(true));

        assertThat(out).extracting(Document::getId).containsExactly("a");
    }

    @Test
    void 向量路异常_降级为关键词() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("vector store down"));
        when(keywordSearchDao.search(any(), any(RetrievalScope.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new KeywordSearchDao.KeywordHit("c", "关键词内容c", Map.of())));

        List<Document> out = service.search("查询", scope(), strategy(true));

        assertThat(out).extracting(Document::getId).containsExactly("c");
    }

    @Test
    void 融合结果携带来源元数据_source字段透传() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("a", "向量内容a", Map.of("source", "手册A"))));
        when(keywordSearchDao.search(any(), any(RetrievalScope.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new KeywordSearchDao.KeywordHit("c", "关键词内容c", Map.of("source", "手册C"))));

        List<Document> out = service.search("查询", scope(), strategy(true));

        Document docA = out.stream().filter(d -> "a".equals(d.getId())).findFirst().orElseThrow();
        Document docC = out.stream().filter(d -> "c".equals(d.getId())).findFirst().orElseThrow();
        assertThat(docA.getMetadata().get("source")).isEqualTo("手册A");
        assertThat(docC.getMetadata().get("source")).isEqualTo("手册C");
    }
}
