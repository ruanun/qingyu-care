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

    private Document vecDoc(String id) {
        return new Document(id, "向量内容" + id, Map.of());
    }

    @Test
    void 两路融合_关键词命中项进入结果() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(vecDoc("a"), vecDoc("b")));
        when(keywordSearchDao.search(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new KeywordSearchDao.KeywordHit("c", "关键词内容c", Map.of())));

        List<Document> out = service.search("查询", "1", strategy(true));

        assertThat(out).extracting(Document::getId).contains("a", "b", "c");
    }

    @Test
    void 关键词路异常_降级为纯向量() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(vecDoc("a")));
        when(keywordSearchDao.search(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new RuntimeException("fts down"));

        List<Document> out = service.search("查询", "1", strategy(true));

        assertThat(out).extracting(Document::getId).containsExactly("a");
    }
}
