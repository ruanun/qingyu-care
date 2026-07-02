package com.qingyu.care.enums;

/**
 * 会话状态枚举
 */
public enum SessionStatus {
    /**
     * AI处理中
     */
    AI_PROCESSING,
    
    /**
     * 人工客服处理中
     */
    AGENT_PROCESSING,
    
    /**
     * 等待人工接管
     */
    WAITING_AGENT,
    
    /**
     * 已结束
     */
    ENDED,
    
    /**
     * 超时结束
     */
    TIMEOUT
}
