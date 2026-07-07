package com.qingyu.care.service;

import com.qingyu.care.entity.ChatStrategy;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 混合检索：向量 + 关键词两路，RRF 融合。
 *
 * @author ruan
 */
public interface HybridRetrievalService {

    /**
     * @param query    用户查询
     * @param scope    检索范围（租户 + 应用 + enabled 知识库集合，调用方保证非空范围）
     * @param strategy 提供 topK/similarityThreshold/keywordTopK/rrfK
     * @return 融合后的 chunk（Spring AI Document），供 buildContext 消费
     */
    List<Document> search(String query, RetrievalScope scope, ChatStrategy strategy);
}
