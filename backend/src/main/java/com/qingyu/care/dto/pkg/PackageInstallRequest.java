package com.qingyu.care.dto.pkg;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 安装请求：变量名 → 租户填的取值。
 *
 * <p>⚠️ <b>这里没有 tenantId / appId / scope</b>：目标租户与应用由 {@code TenantContext} 决定
 * （Sa-Token 会话 + {@code X-App-Id} 头），归属一律服务端强制（spec §3.4）。
 *
 * @author ruan
 */
@Data
@NoArgsConstructor
public class PackageInstallRequest {

    private Map<String, String> variables;

    /**
     * ⚠️ <b>显式 toString：只输出变量名，绝不输出取值。</b>
     *
     * <p>本 DTO 会流经 {@code AuditLogAspect}——它用 {@code Arrays.toString(pjp.getArgs())} 把整个
     * 入参串起来落进 {@code sys_audit_log.detail}，而它的脱敏正则是<b>按字段名</b>匹配的
     * （{@code apikey|password|secret|appsecret|authheader}）。既有路径之所以安全，纯粹因为实体
     * 字段就叫 {@code authHeader}、恰好命中。
     *
     * <p>而这里的凭据装在 {@code Map<String,String>} 里，键是<b>平台管理员在 manifest 里自定义
     * 的变量名</b>（导出时生成的形状是 {@code <KEY>_AUTH}，如 {@code WEATHER_AUTH}），一个敏感词
     * 都不含——用 Lombok 的默认 toString 会把租户填的第三方 API Key <b>明文</b>写进审计表。而审计
     * 日志端点允许平台管理员查询全平台（{@code tenantId == null} 时不收窄），等于平台方能读到每个
     * 租户的密钥，直接推翻本特性「租户填自己的凭据」这个相对平台共享工具的核心卖点，也与仓库既有
     * 的三处凭据防线自相矛盾（前端不回显、{@code SharedCredentialMasker}、{@code getTool} 用写级
     * 守卫防读凭据）。
     *
     * <p>审计价值不受影响：谁、何时、装的哪个包、给了哪些变量名，全都还在。
     */
    @Override
    public String toString() {
        return "PackageInstallRequest(variables="
                + (variables == null ? "[]" : variables.keySet()) + ")";
    }
}
