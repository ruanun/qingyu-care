package com.qingyu.care.dto.openapi;

import java.util.List;

/**
 * @param candidates     可导入的候选工具（已标好 nameConflict）
 * @param globalWarnings 「压根没能成为候选」的说明（被跳过的方法、非对象请求体、超上限截断）
 * @param truncated      是否因超过上限而截断
 * @author ruan
 */
public record OpenApiParseResponse(List<OpenApiToolCandidate> candidates,
                                   List<String> globalWarnings,
                                   boolean truncated) {
}
