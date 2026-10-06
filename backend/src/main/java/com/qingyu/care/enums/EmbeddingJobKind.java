package com.qingyu.care.enums;

/**
 * 向量任务的种类。
 *
 * @author ruan
 */
public enum EmbeddingJobKind {
    /** 切换到另一个向量模型：影子重建，完成后翻转指针、清旧标签 */
    SWITCH,
    /**
     * 用当前模型原地重建：模型没换、向量的输入或切分方式变了（如 2026-10-06 起只嵌入正文）。
     * 标签不变，所以不走影子重建——逐篇在一个事务里「删本篇该标签的向量 + 写新向量」，提交前检索看到的仍是旧向量。
     */
    REBUILD;

    /** 存量任务行 kind 为 NULL，按 SWITCH 理解 */
    public static EmbeddingJobKind orDefault(EmbeddingJobKind kind) {
        return kind != null ? kind : SWITCH;
    }
}
