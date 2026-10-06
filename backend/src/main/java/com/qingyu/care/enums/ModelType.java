package com.qingyu.care.enums;

/**
 * 模型类型：同一张 {@code ai_model_config} 表里区分对话模型与向量模型。
 *
 * <p>⚠️ 存量行的列值为 NULL（列是后加的），一律按 {@link #CHAT} 理解——所有按类型筛选的查询都要写成
 * {@code model_type IS NULL OR model_type = 'CHAT'}，只判等于 CHAT 会让存量对话模型整体消失。
 *
 * @author ruan
 */
public enum ModelType {
    /** 对话模型：参与按场景的对话路由与故障降级 */
    CHAT,
    /** 向量模型：只用于知识库的向量化与检索，由应用钉住一条，永不参与对话路由 */
    EMBEDDING;

    /** 把存量 NULL 归一成 CHAT */
    public static ModelType orDefault(ModelType type) {
        return type != null ? type : CHAT;
    }
}
