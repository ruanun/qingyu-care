package com.qingyu.care.controller;

import com.qingyu.care.dto.ApiResponse;
import com.qingyu.care.dto.DashboardStatsDTO;
import com.qingyu.care.entity.ChatSession;
import com.qingyu.care.enums.SessionStatus;
import com.qingyu.care.repository.ChatSessionRepository;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import com.qingyu.care.repository.DocumentRepository;
import com.qingyu.care.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/v1/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard", description = "系统仪表盘 API")
public class DashboardController {
    
    private final ChatSessionRepository sessionRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final ChatService chatService;
    
    @GetMapping("/stats")
    @Operation(summary = "获取仪表盘统计数据", description = "返回系统关键指标")
    public ApiResponse<DashboardStatsDTO> getStats() {
        // 会话统计
        long totalSessions = sessionRepository.count();
        LocalDateTime startOfToday = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        long todaySessions = sessionRepository.countTodaySessions(startOfToday);
        
        List<SessionStatus> activeStatuses = List.of(
                SessionStatus.AI_PROCESSING,
                SessionStatus.WAITING_AGENT,
                SessionStatus.AGENT_PROCESSING
        );
        long activeSessions = sessionRepository.countByStatusIn(activeStatuses);
        long waitingAgentSessions = sessionRepository.countByStatus(SessionStatus.WAITING_AGENT);
        
        // 知识库统计
        long totalKnowledgeBases = knowledgeBaseRepository.count();
        long totalDocuments = documentRepository.count();
        
        // AI 解决率计算
        long endedSessions = sessionRepository.countEndedSessions();
        long aiResolvedSessions = sessionRepository.countAiResolvedSessions();
        double aiResolutionRate = endedSessions > 0 
                ? (double) aiResolvedSessions / endedSessions * 100 
                : 0.0;
        
        DashboardStatsDTO stats = DashboardStatsDTO.builder()
                .totalSessions(totalSessions)
                .todaySessions(todaySessions)
                .activeSessions(activeSessions)
                .waitingAgentSessions(waitingAgentSessions)
                .totalKnowledgeBases(totalKnowledgeBases)
                .totalDocuments(totalDocuments)
                .aiResolutionRate(Math.round(aiResolutionRate * 10) / 10.0)
                .build();
        
        return ApiResponse.success(stats);
    }
    
    @GetMapping("/recent-sessions")
    @Operation(summary = "获取最近会话", description = "返回最近的会话列表")
    public ApiResponse<List<ChatSession>> getRecentSessions(
            @RequestParam(defaultValue = "5") int limit) {
        // 改走 ChatService（复用已按 TenantContext 收窄的 getAllSessions），不再直连 repository
        Page<ChatSession> recentSessions = chatService.getAllSessions(null, PageRequest.of(0, limit));
        return ApiResponse.success(recentSessions.getContent());
    }
}
