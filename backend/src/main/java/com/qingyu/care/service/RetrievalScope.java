package com.qingyu.care.service;

import com.qingyu.care.enums.DocumentVisibility;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 检索范围值对象：当前请求合法可检索的（租户、应用、enabled 知识库集合、调用方访问级别）。
 * isEmpty() 为真表示无合法范围（无应用上下文 / 无启用知识库），检索应短路返回空。
 * accessLevel：PUBLIC=只见公开文档，INTERNAL=全见（按鉴权路径判定，见 RetrievalScopeResolver）。
 * accessLevel 为 null 时不加 visibility 条件（仅出现在直接构造的测试场景；生产路径由 Resolver 兜底 PUBLIC）。
 *
 * @author ruan
 */
public record RetrievalScope(Long tenantId, Long appId, List<Long> kbIds, DocumentVisibility accessLevel) {

    /** 空范围（保留 tenantId 便于日志排查；accessLevel 无意义置 null）。 */
    public static RetrievalScope empty(Long tenantId) {
        return new RetrievalScope(tenantId, null, List.of(), null);
    }

    public boolean isEmpty() {
        return appId == null || kbIds == null || kbIds.isEmpty();
    }

    /**
     * 拼接 Spring AI 向量路 filterExpression：
     * tenantId == 'T' && appId == 'A' && knowledgeBaseId in ['1','2'] && docEnabled == 'true' [&& visibility == 'PUBLIC']
     * 一律等值条件（metadata 缺键行做 != 判断时 SQL 为 NULL→false 会静默筛掉，故靠摄取写全键+启动回填保证键存在）。
     * 仅在非空范围下调用，空范围抛 IllegalStateException。
     */
    public String toFilterExpression() {
        if (isEmpty()) {
            throw new IllegalStateException("空检索范围不可拼接过滤表达式（调用方应先判 isEmpty() 短路）");
        }
        StringBuilder sb = new StringBuilder();
        if (tenantId != null) {
            sb.append("tenantId == '").append(tenantId).append("' && ");
        }
        sb.append("appId == '").append(appId).append("'");
        sb.append(" && knowledgeBaseId in [")
          .append(kbIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(",")))
          .append("]");
        sb.append(" && docEnabled == 'true'");
        if (accessLevel == DocumentVisibility.PUBLIC) {
            sb.append(" && visibility == 'PUBLIC'");
        }
        return sb.toString();
    }
}
