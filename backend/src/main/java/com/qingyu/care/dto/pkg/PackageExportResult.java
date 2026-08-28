package com.qingyu.care.dto.pkg;

import com.qingyu.care.entity.ToolPackage;

import java.util.List;

/**
 * 导出结果。
 *
 * <p>{@code warnings} <b>不阻断导出</b>（spec §4.1）：它们是启发式（skill 绑了集合外的工具、
 * URL 里疑似嵌了密钥），最终仍要平台管理员逐字看一遍 manifest 预览再发布——同噪声剔除，
 * 没法自动化。前端必须把它们显眼地展示出来，不能只在某个折叠区里带一句。
 *
 * @author ruan
 */
public record PackageExportResult(ToolPackage toolPackage, List<String> warnings) {
}
