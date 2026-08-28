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
}
