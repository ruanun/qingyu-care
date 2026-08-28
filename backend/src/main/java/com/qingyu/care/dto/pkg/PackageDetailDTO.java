package com.qingyu.care.dto.pkg;

import java.util.List;

/**
 * 租户侧看到的包详情：够他决定装不装、以及知道要填什么。
 *
 * <p>⚠️ <b>刻意不返回 manifest 全文</b>：里面的 {@code urlTemplate} / MCP 地址是平台侧的实现
 * 细节，租户拿到既无用又扩大暴露面（含平台内网地址的可能）。租户真正需要的是变量表——那是
 * 他要填的东西——与「里面有些什么」的名字清单。
 *
 * @author ruan
 */
public record PackageDetailDTO(
        String code,
        String name,
        String description,
        List<PackageManifest.VariableSpec> variables,
        List<String> toolNames,
        List<String> mcpServerNames,
        List<String> skillNames) {
}
