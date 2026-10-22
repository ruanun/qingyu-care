package com.qingyu.care.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion：对多路排名列表按 score(item)=Σ 1/(k+rank) 融合。
 * 基于排名、无需分数归一化（向量 cosine 与 ts_rank 量纲不可比）。按 id 去重。
 *
 * @author ruan
 */
public final class RrfFuser {

    private RrfFuser() {
    }

    /** 融合项：id 用于去重与跨列表对齐（此处即 vector_store 行 id）。metadata 随 Item 原样携带穿过融合。 */
    public record Item(String id, String content, Map<String, Object> metadata) {
    }

    /**
     * @param rankedLists 各路的排名列表（每路已按相关性降序）
     * @param k           RRF 常数（常用 60）
     * @param topK        最终返回条数
     */
    public static List<Item> fuse(List<List<Item>> rankedLists, int k, int topK) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Item> items = new LinkedHashMap<>();
        for (List<Item> list : rankedLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                Item it = list.get(rank);
                scores.merge(it.id(), 1.0 / (k + rank + 1), Double::sum); // rank 从 1 起 → +1
                items.putIfAbsent(it.id(), it);
            }
        }
        List<Map.Entry<String, Double>> ordered = new ArrayList<>(scores.entrySet());
        ordered.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed());
        List<Item> result = new ArrayList<>();
        for (Map.Entry<String, Double> e : ordered) {
            if (result.size() >= topK) {
                break;
            }
            result.add(items.get(e.getKey()));
        }
        return result;
    }
}
