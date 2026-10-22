package com.qingyu.care.service.openapi;

import com.qingyu.care.exception.BusinessException;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * schema 文本的解析与版本判别。
 *
 * <p>JSON 与 YAML 走的是两条不同的反序列化分支，各测一条；版本判别必须在反序列化**之前**
 * 做——Swagger 2.0 文档反序列化成 {@code OpenAPI} 只会得到一堆 null，报不出有用的原因。
 *
 * @author ruan
 */
class OpenApiSchemaParserTest {

    private final OpenApiSchemaParser parser = new OpenApiSchemaParser();

    private static final String JSON_3_0 = """
            {
              "openapi": "3.0.3",
              "info": {"title": "订单 API", "version": "1.0"},
              "servers": [{"url": "https://api.example.com"}],
              "paths": {
                "/orders/{orderId}": {
                  "get": {
                    "operationId": "getOrder",
                    "summary": "查订单",
                    "parameters": [
                      {"name": "orderId", "in": "path", "required": true,
                       "schema": {"type": "string"}}
                    ]
                  }
                }
              }
            }""";

    private static final String YAML_3_1 = """
            openapi: 3.1.0
            info:
              title: 订单 API
              version: '1.0'
            servers:
              - url: https://api.example.com
            paths:
              /orders:
                get:
                  operationId: listOrders
                  summary: 列订单
            """;

    @Test
    void JSON格式的3_0文档能解析出路径与服务器() {
        OpenAPI api = parser.parse(JSON_3_0);

        assertThat(api.getOpenapi()).startsWith("3.0");
        assertThat(api.getServers().get(0).getUrl()).isEqualTo("https://api.example.com");
        assertThat(api.getPaths()).containsKey("/orders/{orderId}");
        assertThat(api.getPaths().get("/orders/{orderId}").getGet().getOperationId())
                .isEqualTo("getOrder");
    }

    @Test
    void YAML格式的3_1文档能解析() {
        OpenAPI api = parser.parse(YAML_3_1);

        assertThat(api.getOpenapi()).startsWith("3.1");
        assertThat(api.getPaths().get("/orders").getGet().getOperationId()).isEqualTo("listOrders");
    }

    @Test
    void Swagger2_0给出点名版本的错误而不是含糊的解析失败() {
        String swagger2 = """
                {"swagger": "2.0", "info": {"title": "旧 API", "version": "1.0"}, "paths": {}}""";

        assertThatThrownBy(() -> parser.parse(swagger2))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("3.x")
                .hasMessageContaining("2.0");
    }

    @Test
    void 缺少openapi字段的文档被拒() {
        assertThatThrownBy(() -> parser.parse("{\"info\": {\"title\": \"x\"}}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("OpenAPI");
    }

    @Test
    void 非法文本抛业务异常而不是原始解析器异常() {
        // 原始的 Jackson/SnakeYAML 异常消息会把解析器内部细节暴露给前端
        assertThatThrownBy(() -> parser.parse("这不是一份 schema {{{"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 空文本被拒() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(BusinessException.class);
    }
}
