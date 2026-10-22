package com.qingyu.care.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在控制器类上：该类所有写端点（POST/PUT/DELETE/PATCH）只允许租户管理员与平台管理员调用，
 * 读端点不受影响。由 {@link com.qingyu.care.config.AdminOnlyWritesAspect} 强制。
 *
 * <p>为什么做成类级注解而不是在每个方法里写 {@code SecurityUtil.requireTenantAdmin()}：后者漏一处
 * 不报错、不打日志，而本仓库确实漏过——2026-09-25 安全扫描发现 10 个应用级配置控制器、50 多个
 * 写端点对坐席（TENANT_MEMBER）完全放开，坐席能改知识库、Prompt、模型、工具与 MCP。
 * {@code WriteEndpointRoleGuardTest} 扫描全部控制器，有写端点的类必须标本注解或登记手动校验的理由。
 *
 * @author ruan
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AdminOnlyWrites {
}
