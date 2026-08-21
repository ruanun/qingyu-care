package com.qingyu.care.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量标注 MCP 工具只读位。
 *
 * @author ruan
 */
@Data
public class McpToolReadOnlyRequest {

    @NotEmpty(message = "工具 id 不能为空")
    private List<Long> toolIds;

    @NotNull(message = "必须指定只读位")
    private Boolean readOnly;
}
