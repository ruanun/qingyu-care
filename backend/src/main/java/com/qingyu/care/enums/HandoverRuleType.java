package com.qingyu.care.enums;

/**
 * 转人工规则类型
 */
public enum HandoverRuleType {
    /**
     * 关键词匹配
     */
    KEYWORD,
    
    /**
     * 低置信度
     */
    LOW_CONFIDENCE,
    
    /**
     * 连续未命中知识库
     */
    NO_HIT_STREAK
}
