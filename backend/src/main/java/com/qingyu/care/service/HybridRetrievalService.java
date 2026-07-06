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
     * @param tenantId 当前租户（隔离）
     * @param strategy 提供 topK/similarityThreshold/keywordTopK/rrfK
     * @return 融合后的 chunk（Spring AI Document），供 buildContext 消费
     */
    List<Document> search(String query, String tenantId, ChatStrategy strategy);
}
