package com.qingyu.care.dto.pkg;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 能力包的局部更新请求体。
 *
 * <p>⚠️ <b>三个字段都不写初始化器</b>（含 {@code @Builder.Default}）：实体字段的初始化器在
 * Jackson 走无参构造反序列化时照样生效，请求体没给的字段会拿到类默认值而非 {@code null}，
 * 于是 {@code if (x != null)} 恒真、局部 PUT 把库里的值静默覆盖回默认值。本仓库为此付过一次
 * 真实代价（点一下工具启用开关，{@code httpMethod}/{@code timeoutMs} 被打回默认值），由
 * {@code UpdateRequestNoInitializerTest} 反射强制。
 *
 * <p>⚠️ <b>刻意没有 {@code code} 与 {@code status}</b>：前者是包的寻址标识（租户安装端点用它），
 * 后者只能经 {@code publish}/{@code archive} 两个专用端点改——理由与登记见
 * {@code UpdateRequestFieldCoverageTest} 的白名单条目。
 *
 * @author ruan
 */
@Data
@NoArgsConstructor
public class ToolPackageUpdateRequest {

    private String name;

    private String description;

    /** manifest 全文（JSON）。给了就必须能解析通过，解析不过整个更新被拒。 */
    private String manifest;
}
