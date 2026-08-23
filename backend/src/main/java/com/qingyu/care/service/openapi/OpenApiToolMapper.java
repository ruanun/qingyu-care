package com.qingyu.care.service.openapi;

import com.qingyu.care.dto.openapi.OpenApiToolCandidate;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link OpenAPI} → 候选工具列表。**纯函数**：给定 schema 与 baseUrl，输出完全确定，
 * 不碰 DB、不发请求。本特性的映射规则几乎全在这里，故单测也集中在这里。
 *
 * <p>⚠️ warnings 分两级，别混成一个列表：
 * <ul>
 *   <li><b>候选级</b>挂在具体候选上（描述截断、header 参数被忽略、$ref 降级），随它显示
 *       在勾选列表里；
 *   <li><b>全局级</b>描述「压根没能成为候选」的那些（PUT/DELETE 被跳过、requestBody 非
 *       object 被跳过、超出上限被截断）——它们没有候选行可挂，只做候选级 warning 的话这些
 *       operation 会<b>无声消失</b>，用户只会觉得「我那个端点怎么没导进来」。
 * </ul>
 *
 * @author ruan
 */
@Component
@RequiredArgsConstructor
public class OpenApiToolMapper {

    /** 候选数量上限，防一份上千 operation 的 schema 撑爆预览界面 */
    static final int MAX_CANDIDATES = 200;
    /** AiTool.description 的列宽 */
    static final int MAX_DESCRIPTION = 500;
    /** ⚠️ 不是 AiTool.name 的列宽 100：64 是 OpenAI 兼容端点对函数名的限制 */
    static final int MAX_NAME = 64;

    private final ObjectMapper objectMapper;

    public record MapResult(List<OpenApiToolCandidate> candidates,
                            List<String> globalWarnings,
                            boolean truncated) {
    }

    public MapResult map(OpenAPI api, String baseUrl) {
        List<OpenApiToolCandidate> candidates = new ArrayList<>();
        List<String> globalWarnings = new ArrayList<>();
        Set<String> usedNames = new LinkedHashSet<>();
        boolean truncated = false;

        String base = normalizeBaseUrl(baseUrl, globalWarnings);
        if (api.getPaths() == null || api.getPaths().isEmpty()) {
            globalWarnings.add("schema 里没有任何路径");
            return new MapResult(candidates, globalWarnings, false);
        }

        outer:
        for (Map.Entry<String, PathItem> pathEntry : api.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem item = pathEntry.getValue();
            for (Map.Entry<PathItem.HttpMethod, Operation> opEntry : item.readOperationsMap().entrySet()) {
                PathItem.HttpMethod method = opEntry.getKey();
                Operation op = opEntry.getValue();

                if (method != PathItem.HttpMethod.GET && method != PathItem.HttpMethod.POST) {
                    globalWarnings.add("已跳过 %s %s（%s）：本平台的工具只支持 GET 与 POST"
                            .formatted(method, path, op.getOperationId() == null ? "无 operationId" : op.getOperationId()));
                    continue;
                }
                if (candidates.size() >= MAX_CANDIDATES) {
                    truncated = true;
                    globalWarnings.add("端点数超过 " + MAX_CANDIDATES + " 个上限，只保留了前 "
                            + MAX_CANDIDATES + " 个，请分批导入");
                    break outer;
                }

                OpenApiToolCandidate candidate = toCandidate(api, path, item, method, op, base, usedNames);
                if (candidate == null) {
                    // toCandidate 内部已记全局 warning（如 requestBody 非 object）
                    globalWarnings.add("已跳过 %s %s：请求体不是对象结构，当前工具模型无法表达"
                            .formatted(method, path));
                    continue;
                }
                candidates.add(candidate);
            }
        }
        return new MapResult(candidates, globalWarnings, truncated);
    }

    /** @return null 表示该 operation 无法表达、应跳过 */
    private OpenApiToolCandidate toCandidate(OpenAPI api, String path, PathItem item, PathItem.HttpMethod method,
            Operation op, String baseUrl, Set<String> usedNames) {
        OpenApiToolCandidate c = new OpenApiToolCandidate();
        List<String> warnings = new ArrayList<>();

        c.setName(uniqueName(rawName(op, method, path), usedNames));
        c.setHttpMethod(method.name());
        c.setDescription(description(op, method, path, warnings));

        List<Parameter> params = mergeParameters(item, op);
        warnIgnoredParams(params, warnings);
        c.setUrlTemplate(buildUrlTemplate(baseUrl, path, params));

        // inputSchema 由 Task 4 填充；本任务先置空对象，保证候选结构完整
        c.setInputSchema("{\"type\":\"object\",\"properties\":{}}");

        c.setWarnings(warnings);
        return c;
    }

    // ===== 参数合并（路径级 + 操作级） =====

    /**
     * OpenAPI 3.x 允许在 {@code PathItem} 级别声明对该路径下所有 operation 都生效的共享
     * 参数（真实 schema 很常见：同一资源的多个方法共享 id 或过滤参数，Petstore 就有）。
     * 漏合并的后果比表面看起来更严重：路径级 <b>query</b> 参数会完全不出现在 urlTemplate
     * 与 inputSchema 里；路径级 <b>path</b> 参数虽然占位符还在 urlTemplate（直接来自 path
     * 字符串），但 inputSchema 里没有对应属性，LLM 不知道要填，运行时必然撞上
     * {@link com.qingyu.care.service.tool.UrlTemplateFiller} 的「缺少必填参数」——而工具
     * 调用是 fail-soft 的，模型会拿着错误文本继续编答案，全程无异常无日志。
     *
     * <p>合并规则：路径级参数在前、操作级参数在后；操作级覆盖同名的路径级参数，同名判据是
     * {@code name} 与 {@code in} <b>两者都相同</b>——只比 name 会把「query 里的 id」与
     * 「header 里的 id」错误合并成一个参数。
     */
    private List<Parameter> mergeParameters(PathItem item, Operation op) {
        List<Parameter> pathParams = item.getParameters() == null ? List.of() : item.getParameters();
        List<Parameter> opParams = op.getParameters() == null ? List.of() : op.getParameters();
        if (pathParams.isEmpty()) {
            return opParams;
        }
        if (opParams.isEmpty()) {
            return pathParams;
        }
        Set<String> overriddenByOp = new LinkedHashSet<>();
        for (Parameter p : opParams) {
            overriddenByOp.add(paramKey(p));
        }
        List<Parameter> merged = new ArrayList<>();
        for (Parameter p : pathParams) {
            if (!overriddenByOp.contains(paramKey(p))) {
                merged.add(p);
            }
        }
        merged.addAll(opParams);
        return merged;
    }

    /** 参数同名判据：{@code in} 与 {@code name} 拼接，两者都相同才算同一参数。 */
    private String paramKey(Parameter p) {
        return p.getIn() + ":" + p.getName();
    }

    // ===== 名字 =====

    private String rawName(Operation op, PathItem.HttpMethod method, String path) {
        if (op.getOperationId() != null && !op.getOperationId().isBlank()) {
            return sanitize(op.getOperationId());
        }
        return sanitize(method.name().toLowerCase() + "_" + path);
    }

    /** 清洗成 [A-Za-z0-9_]，首字符非字母时前置 t_，截断到 {@link #MAX_NAME}。 */
    static String sanitize(String raw) {
        String s = raw.replaceAll("[^A-Za-z0-9_]", "_").replaceAll("_+", "_");
        s = s.replaceAll("^_+", "").replaceAll("_+$", "");
        if (s.isEmpty()) {
            s = "tool";
        }
        if (!Character.isLetter(s.charAt(0))) {
            s = "t_" + s;
        }
        return s.length() <= MAX_NAME ? s : s.substring(0, MAX_NAME);
    }

    /** 批内重名加 _2/_3，且保证加完后仍不超过 {@link #MAX_NAME}。 */
    private String uniqueName(String base, Set<String> used) {
        if (used.add(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String suffix = "_" + n;
            String head = base.length() + suffix.length() <= MAX_NAME
                    ? base
                    : base.substring(0, MAX_NAME - suffix.length());
            String candidate = head + suffix;
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }

    // ===== 描述 =====

    private String description(Operation op, PathItem.HttpMethod method, String path,
            List<String> warnings) {
        String text = op.getSummary();
        if (text == null || text.isBlank()) {
            text = op.getDescription();
        }
        if (text == null || text.isBlank()) {
            return method.name() + " " + path;
        }
        if (text.length() > MAX_DESCRIPTION) {
            warnings.add("描述过长已截断到 " + MAX_DESCRIPTION + " 字，建议手工改写得更贴合调用时机");
            return text.substring(0, MAX_DESCRIPTION);
        }
        return text;
    }

    // ===== urlTemplate =====

    /**
     * baseUrl + path + query 串。OpenAPI 的 path 占位符 {@code {orderId}} 与本项目
     * {@link com.qingyu.care.service.tool.UrlTemplateFiller} 的 {@code {key}} 语法完全一致，
     * 直接原样拼。query 参数按 schema 原始顺序追加（不按 required 重排——顺序稳定才可预期，
     * 且 UrlTemplateFiller 能正确处理任意位置的可选参数）。
     */
    private String buildUrlTemplate(String base, String path, List<Parameter> params) {
        StringBuilder url = new StringBuilder(base).append(path);
        String separator = "?";
        for (Parameter p : params) {
            if (!"query".equals(p.getIn())) {
                continue;
            }
            url.append(separator).append(p.getName()).append("={").append(p.getName()).append("}");
            separator = "&";
        }
        return url.toString();
    }

    /**
     * 规范化服务器地址：去掉末尾斜杠、剥掉 query。
     *
     * <p>⚠️ 剥 query 不是洁癖：拼接顺序是 base + path + {@code ?query}，base 自带 query 会
     * 拼出 {@code .../api?v=1/search&q={q}} 这种 path 跑到 query 后面的坏 URL，请求必然失败。
     * OpenAPI 的 {@code servers.url} 本就不该带 query，剥掉并告知比硬拼一个坏地址诚实。
     */
    private String normalizeBaseUrl(String baseUrl, List<String> globalWarnings) {
        if (baseUrl == null || baseUrl.isBlank()) {
            globalWarnings.add("schema 未提供绝对的服务器地址，请手工填写服务器地址后再导入");
            return "";
        }
        String base = baseUrl.trim();
        int q = base.indexOf('?');
        if (q >= 0) {
            globalWarnings.add("服务器地址里的查询参数已被忽略（" + base.substring(q)
                    + "）：它会让路径拼到查询串后面，请把这类参数写进各端点自身的参数里");
            base = base.substring(0, q);
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /**
     * header / cookie 参数在 AiTool 模型里无法表达（只有一个静态 authHeader，没有按调用
     * 填充的 header 通道）。**必须记 warning**：少了认证 header 的工具会永远返回 401，而
     * 工具调用是 fail-soft 的——模型拿着错误响应继续编答案，全程无异常无日志。
     */
    private void warnIgnoredParams(List<Parameter> params, List<String> warnings) {
        List<String> ignored = params.stream()
                .filter(p -> "header".equals(p.getIn()) || "cookie".equals(p.getIn()))
                .map(Parameter::getName)
                .toList();
        if (!ignored.isEmpty()) {
            warnings.add("以下 header/cookie 参数无法表达、已忽略：" + String.join("、", ignored)
                    + "。若其中含认证信息，请在下方「鉴权头」里统一填写");
        }
    }
}
