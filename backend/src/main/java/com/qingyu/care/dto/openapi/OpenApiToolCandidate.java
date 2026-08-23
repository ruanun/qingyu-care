package com.qingyu.care.dto.openapi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个待导入的候选工具。**parse 产出它、import 收它**，是同一个类型——这样前端在预览里
 * 改过的 name/description 能原样回传，不需要后端二次解析 schema（两次解析可能因 schema
 * 内容变化或解析器版本差异而分叉，用户看到的与实际落库的会悄悄不一致，见设计文档 §2.5）。
 *
 * <p>⚠️ 不用 record：import 端点要反序列化它，且前端会改其中的字段，普通可变 DTO 更贴合
 * 本仓库既有 DTO 的写法。
 *
 * @author ruan
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpenApiToolCandidate {

    private String name;
    private String description;
    /** 只可能是 GET 或 POST，见设计文档 §2.4 */
    private String httpMethod;
    private String urlTemplate;
    /** JSON Schema 文本，由 Task 4 构建 */
    private String inputSchema;

    /**
     * 候选级 warning：这个工具**能**导入，但有能力被降级（header 参数被忽略、描述被截断、
     * $ref 被降级、名字与既有工具冲突）。必须显示在勾选列表里就近可见——不这样用户会以为
     * 导进来就能用，而一个「因为忽略了 header 认证参数所以永远返回 401」的工具在运行时是
     * fail-soft 的：模型拿着错误响应继续编答案，全程无异常无日志。
     */
    private List<String> warnings = new ArrayList<>();

    /** 与当前租户已有工具重名。前端据此高亮并要求改名后才能提交。 */
    private boolean nameConflict;
}
