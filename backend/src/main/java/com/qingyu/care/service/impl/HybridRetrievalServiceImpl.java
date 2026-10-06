package com.qingyu.care.service.impl;

import com.qingyu.care.entity.ChatStrategy;
import com.qingyu.care.repository.KeywordSearchDao;
import com.qingyu.care.entity.AiModelConfig;
import com.qingyu.care.service.embedding.EmbeddingModelRegistry;
import com.qingyu.care.service.embedding.EmbeddingTag;
import com.qingyu.care.service.HybridRetrievalService;
import com.qingyu.care.service.RetrievalScope;
import com.qingyu.care.service.RrfFuser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
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

    private final EmbeddingModelRegistry embeddingModelRegistry;
    private final KeywordSearchDao keywordSearchDao;

    @Override
    public List<Document> search(String query, RetrievalScope scope, ChatStrategy strategy, AiModelConfig embeddingModel) {
        int topK = strategy.effectiveTopK();
        int keywordTopK = strategy.effectiveKeywordTopK();
        int rrfK = strategy.getRrfK() != null ? strategy.getRrfK() : 60;
        // 默认跟随向量模型的推荐阈值（相似度刻度随模型平移，见 ChatStrategy.effectiveSimilarityThreshold）
        double threshold = strategy.effectiveSimilarityThreshold(embeddingModel.getSimilarityThreshold());
        String tag = EmbeddingTag.of(embeddingModel);

        // 向量路（fail-soft：异常则空列表 → 融合退化为关键词/空）
        List<RrfFuser.Item> vectorItems = new ArrayList<>();
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .filterExpression(scope.toFilterExpression(tag))
                    .build();
            for (Document d : embeddingModelRegistry.vectorStore(embeddingModel).similaritySearch(searchRequest)) {
                String content = d.getText() != null ? d.getText() : "";
                vectorItems.add(new RrfFuser.Item(d.getId(), content, d.getMetadata()));
            }
        } catch (Exception e) {
            log.warn("向量检索失败，退化为关键词/空 appId={}", scope.appId(), e);
        }

        // 关键词路（fail-soft：异常则空列表 → 融合退化为纯向量）
        List<RrfFuser.Item> keywordItems = new ArrayList<>();
        try {
            for (KeywordSearchDao.KeywordHit h : keywordSearchDao.search(query, scope, tag, keywordTopK)) {
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
