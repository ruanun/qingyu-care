package com.qingyu.care.service.mcp;

/**
 * 远端工具的本地投影。刻意不直接把 MCP SDK 的 McpSchema.Tool 传出协议层——
 * 那样上层就被 SDK 的类型绑住，且 inputSchema 是 Map 而库里存 JSON 文本。
 *
 * @param inputSchemaJson 已序列化的 JSON Schema 文本（SDK 给的是 Map）
 * @param readOnlyHint    远端自报，可能为 null，**仅作界面建议值、不参与安全判定**
 * @author ruan
 */
public record McpToolDescriptor(String name, String description, String inputSchemaJson, Boolean readOnlyHint) {
}
