package com.qingyu.care.service;

import com.qingyu.care.config.TenantContext;
import com.qingyu.care.entity.KnowledgeBase;
import com.qingyu.care.enums.DocumentVisibility;
import com.qingyu.care.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索范围解析：从 TenantContext 取租户/应用，查当前应用下 enabled 的知识库集合。
 * 严格语义：无应用上下文 → 空范围（不检索）；这同时规避了平台管理员绕过
 * Hibernate Filter 时库列表跨租户泄漏的问题（显式 appId 条件是第二道防线）。
 * fail-soft：库查询异常 → 空范围，不阻断对话。
 * 后续「文档权限控制」（Phase 4.3）在此组件上叠加收窄。
 *
 * @author ruan
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetrievalScopeResolver {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public RetrievalScope resolve() {
        Long tenantId = TenantContext.getTenantId();
        Long appId = TenantContext.getAppId();
        if (appId == null) {
            log.debug("无应用上下文，检索范围为空 tenantId={}", tenantId);
            return RetrievalScope.empty(tenantId);
        }
        try {
            List<Long> kbIds = knowledgeBaseRepository.findByAppIdAndEnabledTrue(appId)
                    .stream().map(KnowledgeBase::getId).toList();
            // 访问级别兜底 PUBLIC（最小权限）；由鉴权拦截器按路径写入 TenantContext 的接线在 Task 3 完成
            return new RetrievalScope(tenantId, appId, kbIds, DocumentVisibility.PUBLIC);
        } catch (Exception e) {
            log.warn("检索范围解析失败，fail-soft 为空范围 appId={}", appId, e);
            return RetrievalScope.empty(tenantId);
        }
    }
}
