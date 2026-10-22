package com.qingyu.care.dto.pkg;

import java.util.List;

/**
 * 安装摘要：建了哪些行、哪些需要后续操作。
 *
 * <p>{@code warnings} 承载「部分成功」（spec §4.3）——最典型的是某个 MCP 服务同步失败：
 * 服务本身已经装好了，但它的工具清单还没拉下来，技能里那几个 MCP 工具的绑定就还空着。
 * 界面必须把这句话显眼地摆出来，否则租户会以为装完就能用。
 *
 * @author ruan
 */
public record PackageInstallResult(
        List<String> toolNames,
        List<String> mcpServerNames,
        List<String> skillNames,
        List<String> warnings) {
}
