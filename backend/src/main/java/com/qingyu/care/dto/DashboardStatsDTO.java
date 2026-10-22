package com.qingyu.care.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 系统统计数据 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsDTO {
    
    /**
     * 总会话数
     */
    private long totalSessions;
    
    /**
     * 今日会话数
     */
    private long todaySessions;
    
    /**
     * 活跃会话数 (AI_PROCESSING + WAITING_AGENT + AGENT_PROCESSING)
     */
    private long activeSessions;
    
    /**
     * 待接管会话数
     */
    private long waitingAgentSessions;
    
    /**
     * 知识库数量
     */
    private long totalKnowledgeBases;
    
    /**
     * 文档数量
     */
    private long totalDocuments;
    
    /**
     * AI 解决率 (非人工介入的结束会话占比)
     */
    private double aiResolutionRate;
}
