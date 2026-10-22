package com.qingyu.care.dto.pkg;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 导出请求：平台管理员勾选自己已经调通的工具 / MCP 服务 / 技能，连同包的文案一起提交。
 *
 * <p>spec §2.2：上架方式是「从现成配置导出」而不是包编辑器——导出路径复用的是他本来就要走的
 * 配置流程，产出的东西<b>被真实对话验证过</b>。
 *
 * @author ruan
 */
@Data
@NoArgsConstructor
public class PackageExportRequest {

    /** 包的唯一标识，租户安装端点用它寻址。格式同 MCP code：小写字母开头的字母数字下划线。 */
    private String code;

    private String name;

    private String description;

    private List<Long> toolIds;

    private List<Long> mcpServerIds;

    private List<Long> skillIds;
}
