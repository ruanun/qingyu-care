package com.qingyu.care.service.impl;

import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.KeywordSearchDao;
import com.qingyu.care.service.EmbeddingModelProvider;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.RetrievalScope;
import com.qingyu.care.service.RrfFuser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 混合检索编排：向量 similaritySearch + 关键词 FTS DAO，RRF 融合（按 Document.id 对齐）。
 * fail-soft：向量路与关键词路各自独立 try/catch，任一路异常 → 该路空、由另一路贡献结果；
 * 两路皆失败 → 返回空（RRF 融合空列表），语义正确、不阻断对话。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HybridRetrievalServiceImpl implements HybridRetrievalService {

    private final VectorStore vectorStore;
    private final KeywordSearchDao keywordSearchDao;
    private final EmbeddingModelProvider embeddingModelProvider;

    @Override
    public List<Document> search(String query, RetrievalScope scope, ChatStrategy strategy) {
        // 租户可填任意值、直接打在全平台共享的 pgvector 与全文索引上，这里夹住（2026-09-30 安全审查）
        int topK = Math.max(1, Math.min(strategy.getTopK() != null ? strategy.getTopK() : 5, 50));
        int keywordTopK = Math.max(1, Math.min(strategy.getKeywordTopK() != null ? strategy.getKeywordTopK() : 10, 100));
        int rrfK = strategy.getRrfK() != null ? strategy.getRrfK() : 60;
        // 0.45：与 ChatServiceImpl 的兜底一致（0.7 已被实测标定推翻，见该类注释）。列
        // similarity_threshold 是 nullable=false，这条兜底实际不可达，同样只是避免留一颗
        // 用已推翻的值做的哑弹。
        double threshold = strategy.getSimilarityThreshold() != null ? strategy.getSimilarityThreshold() : 0.45;

        // 向量路（fail-soft：异常则空列表 → 融合退化为关键词/空）
        List<RrfFuser.Item> vectorItems = new ArrayList<>();
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .filterExpression(scope.toFilterExpression(embeddingModelProvider.getModel()))
                    .build();
            for (Document d : vectorStore.similaritySearch(searchRequest)) {
                String content = d.getText() != null ? d.getText() : "";
                vectorItems.add(new RrfFuser.Item(d.getId(), content, d.getMetadata()));
            }
        } catch (Exception e) {
            log.warn("向量检索失败，退化为关键词/空 appId={}", scope.appId(), e);
        }

        // 关键词路（fail-soft：异常则空列表 → 融合退化为纯向量）
        List<RrfFuser.Item> keywordItems = new ArrayList<>();
        try {
            for (KeywordSearchDao.KeywordHit h : keywordSearchDao.search(query, scope, keywordTopK)) {
                String keywordContent = h.content() != null ? h.content() : "";
                keywordItems.add(new RrfFuser.Item(h.id(), keywordContent, h.metadata()));
            }
        } catch (Exception e) {
            log.warn("关键词检索失败，退化为纯向量 appId={}", scope.appId(), e);
        }

        List<RrfFuser.Item> fused = RrfFuser.fuse(List.of(vectorItems, keywordItems), rrfK, topK);
        List<Document> result = new ArrayList<>();
        for (RrfFuser.Item it : fused) {
            result.add(new Document(it.id(), it.content(), it.metadata()));
        }
        return result;
    }
}
