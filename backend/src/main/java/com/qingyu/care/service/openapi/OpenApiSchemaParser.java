package com.qingyu.care.service.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingyu.care.exception.BusinessException;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.stereotype.Component;

/**
 * schema 文本 → {@link OpenAPI} 模型。无 IO、无 DB。
 *
 * <p>⚠️ 本类是全项目**唯一**使用 Jackson 2（{@code com.fasterxml.jackson}）的地方：
 * swagger-core 的 {@code Json31}/{@code Yaml31} 是 Jackson 2 的 mapper，而项目主栈是
 * Jackson 3（{@code tools.jackson}）。两者在类路径上共存、包名不同互不冲突，但**别在
 * 别处混用**——我们自己产出的 JSON（如 inputSchema）一律走项目的 Jackson 3 ObjectMapper。
 *
 * <p>⚠️ 刻意只做反序列化，不解析 {@code $ref}：swagger-parser 那种「自动 resolve 远程
 * $ref」的能力等于一条绕过 {@code SsrfGuard} 的出站路径。本地 {@code $ref} 由后续任务
 * 的 mapper 自己解（带深度上限与循环检测）。
 *
 * @author ruan
 */
@Component
public class OpenApiSchemaParser {

    /**
     * @throws BusinessException 文本为空 / 不是合法 JSON 或 YAML / 不是 OpenAPI 3.x
     */
    public OpenAPI parse(String text) {
        if (text == null || text.isBlank()) {
            throw new BusinessException("schema 内容为空");
        }
        String trimmed = text.trim();
        // 以 { 开头当 JSON，否则当 YAML。YAML 是 JSON 的超集，理论上 Yaml31 也能读 JSON，
        // 但用各自的 mapper 能让报错消息更贴近用户实际给的格式。
        ObjectMapper mapper = trimmed.startsWith("{") ? Json31.mapper() : Yaml31.mapper();

        JsonNode root;
        try {
            root = mapper.readTree(trimmed);
        } catch (Exception e) {
            // 不透传原始异常消息：Jackson/SnakeYAML 的报错含解析器内部细节，对用户无意义
            throw new BusinessException("schema 解析失败，请确认是合法的 JSON 或 YAML");
        }
        requireOpenApi3(root);

        try {
            return mapper.treeToValue(root, OpenAPI.class);
        } catch (Exception e) {
            throw new BusinessException("schema 结构不符合 OpenAPI 规范，无法解析");
        }
    }

    /**
     * 版本判别必须在反序列化**之前**：Swagger 2.0 文档也能被反序列化成 OpenAPI 对象，
     * 只是所有字段都是 null，那时再报错只能说「没有可导入的端点」，用户完全看不出真正原因。
     */
    private void requireOpenApi3(JsonNode root) {
        JsonNode swagger = root.get("swagger");
        if (swagger != null && !swagger.isNull()) {
            throw new BusinessException(
                    "仅支持 OpenAPI 3.x，检测到 Swagger " + swagger.asText() + "，请先转换后再导入");
        }
        JsonNode version = root.get("openapi");
        if (version == null || version.isNull() || !version.asText().startsWith("3.")) {
            throw new BusinessException("仅支持 OpenAPI 3.x：未找到有效的 openapi 版本字段");
        }
    }
}
