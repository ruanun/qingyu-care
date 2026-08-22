package com.qingyu.care.it;

import com.qingyu.care.entity.McpTool;
import com.qingyu.care.repository.McpToolRepository;
import com.qingyu.care.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code McpTool.description} 必须容得下真实 MCP server 的工具描述。
 *
 * <p>⚠️ <b>单测在这里结构上无效</b>：列宽是 DDL 层面的事实，Mockito 把 repository 全换成了桩，
 * 无论列定义写成什么，单测都是绿的。真机冒烟接入 context7（公共 MCP server）时才暴露：
 * 它的工具描述超过 1000 字符，同步直接抛
 * {@code value too long for type character varying(1000)}，整个 server 接不进来。
 *
 * <p>描述文本长度由<b>第三方 server</b> 决定，MCP 规范对它没有任何长度约束，因此不能靠
 * 「大部分描述都很短」来赌——{@code inputSchema} 早已是 {@code text}，{@code description}
 * 当初留 {@code varchar(1000)} 是疏漏而非取舍。
 *
 * <p>⚠️ 本 IT 只能证明<b>新建库</b>的列宽正确（Testcontainers 每次按实体定义重新建表）。
 * <b>存量库另需迁移 SQL</b>：{@code ddl-auto: update} 只加列、不改已有列的类型，改了实体注解
 * 也不会把线上的 {@code varchar(1000)} 变成 {@code text}，见
 * {@code resources/migration/2026-08-22-mcp-tool-description-to-text.sql}。
 *
 * @author ruan
 */
class McpToolLongDescriptionIT extends AbstractPostgresIT {

    @Autowired
    private McpToolRepository toolRepository;

    @Test
    void 工具描述超过一千字符也能落库_否则真实MCP服务接不进来() {
        // 3000 字符：context7 实测的描述长度量级，且远超原来的 varchar(1000)
        String longDescription = "把这段描述当作远端 MCP server 自报的工具说明。".repeat(100);
        assertThat(longDescription.length())
                .as("前提：测试数据必须真的超过原列宽，否则断言退化成永真")
                .isGreaterThan(1000);

        McpTool tool = McpTool.builder()
                .mcpServerId(1L)
                .name("long_description_tool")
                .description(longDescription)
                .readOnly(false)
                .build();

        assertThatCode(() -> toolRepository.saveAndFlush(tool))
                .as("列宽不足时这里抛 DataIntegrityViolationException，整次同步随之 500")
                .doesNotThrowAnyException();

        McpTool reloaded = toolRepository.findById(tool.getId()).orElseThrow();
        assertThat(reloaded.getDescription())
                .as("必须原样存下来，截断会让 LLM 拿到一半的工具说明")
                .isEqualTo(longDescription);
    }
}
