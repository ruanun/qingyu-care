package com.qingyu.care.service.impl;

import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.KeywordSearchDao;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.RrfFuser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @Override
    public List<Document> search(String query, String tenantId, ChatStrategy strategy) {
        int topK = strategy.getTopK() != null ? strategy.getTopK() : 5;
        int keywordTopK = strategy.getKeywordTopK() != null ? strategy.getKeywordTopK() : 10;
        int rrfK = strategy.getRrfK() != null ? strategy.getRrfK() : 60;
        double threshold = strategy.getSimilarityThreshold() != null ? strategy.getSimilarityThreshold() : 0.7;

        // 向量路（fail-soft：异常则空列表 → 融合退化为关键词/空）
        // 与 ChatServiceImpl#searchKnowledge 现状一致的构建方式
        List<RrfFuser.Item> vectorItems = new ArrayList<>();
        try {
            SearchRequest searchRequest = SearchRequest.query(query)
                    .withTopK(topK)
                    .withSimilarityThreshold(threshold);
            if (tenantId != null) {
                searchRequest = searchRequest.withFilterExpression("tenantId == '" + tenantId + "'");
            }
            for (Document d : vectorStore.similaritySearch(searchRequest)) {
                String content = d.getContent() != null ? d.getContent() : "";
                vectorItems.add(new RrfFuser.Item(d.getId(), content));
            }
        } catch (Exception e) {
            log.warn("向量检索失败，退化为关键词/空 tenantId={}", tenantId, e);
        }

        // 关键词路（fail-soft：异常则空列表 → 融合退化为纯向量）
        List<RrfFuser.Item> keywordItems = new ArrayList<>();
        try {
            for (KeywordSearchDao.KeywordHit h : keywordSearchDao.search(query, tenantId, keywordTopK)) {
                keywordItems.add(new RrfFuser.Item(h.id(), h.content()));
            }
        } catch (Exception e) {
            log.warn("关键词检索失败，退化为纯向量 tenantId={}", tenantId, e);
        }

        List<RrfFuser.Item> fused = RrfFuser.fuse(List.of(vectorItems, keywordItems), rrfK, topK);
        List<Document> result = new ArrayList<>();
        for (RrfFuser.Item it : fused) {
            result.add(new Document(it.id(), it.content(), Map.of()));
        }
        return result;
    }
}
