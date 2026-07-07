package com.qingyu.care.service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 检索范围值对象：当前请求合法可检索的（租户、应用、enabled 知识库集合）。
 * isEmpty() 为真表示无合法范围（无应用上下文 / 无启用知识库），检索应短路返回空。
 *
 * @author ruan
 */
public record RetrievalScope(Long tenantId, Long appId, List<Long> kbIds) {

    /** 空范围（保留 tenantId 便于日志排查）。 */
    public static RetrievalScope empty(Long tenantId) {
        return new RetrievalScope(tenantId, null, List.of());
    }

    public boolean isEmpty() {
        return appId == null || kbIds == null || kbIds.isEmpty();
    }

    /**
     * 拼接 Spring AI 向量路 filterExpression：
     * tenantId == 'T' && appId == 'A' && knowledgeBaseId in ['1','2']
     * （metadata 值摄取时均为字符串；tenantId 为 null 时省略该子句，同既有语义）
     * 仅在非空范围下调用。
     */
    public String toFilterExpression() {
        StringBuilder sb = new StringBuilder();
        if (tenantId != null) {
            sb.append("tenantId == '").append(tenantId).append("' && ");
        }
        sb.append("appId == '").append(appId).append("'");
        sb.append(" && knowledgeBaseId in [")
          .append(kbIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(",")))
          .append("]");
        return sb.toString();
    }
}
